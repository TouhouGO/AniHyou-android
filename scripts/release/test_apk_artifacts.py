import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

import apk_artifacts as a


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'version.properties').write_text('name = 1.8.0\ncode = 140\n')
        resources = self.root / 'core/localization/src/main/resources/localization'
        resources.mkdir(parents=True)
        (resources / 'catalog.json').write_text('resource')
        self.inputs = self.root / 'input'
        self.inputs.mkdir()
        self.elements = []
        for abi in sorted(a.ABIS):
            name = f'anihyou-1.8.0-foss-{abi}-release-unsigned.apk'
            with zipfile.ZipFile(self.inputs / name, 'w') as apk:
                apk.writestr('localization/catalog.json', 'resource')
                apk.writestr('assets/dexopt/baseline.prof', b'p')
                apk.writestr('assets/dexopt/baseline.profm', b'm')
            self.elements.append({'type': 'UNIVERSAL' if abi == 'universal' else 'ONE_OF_MANY', 'filters': [] if abi == 'universal' else [{'filterType': 'ABI', 'value': abi}], 'versionCode': 140, 'versionName': '1.8.0', 'outputFile': name})
        self.metadata = {'applicationId': a.PACKAGE, 'variantName': 'fossRelease', 'elements': self.elements}
        self.write_metadata()

    def write_metadata(self):
        (self.inputs / 'output-metadata.json').write_text(json.dumps(self.metadata))

    def test_valid_and_unsafe_path(self):
        self.assertEqual(len(a.validate_unsigned(self.inputs, self.root)), 5)
        self.elements[0]['outputFile'] = '../evil.apk'
        self.write_metadata()
        with self.assertRaises(ValueError):
            a.validate_unsigned(self.inputs, self.root)

    def test_wrong_metadata_version_and_identity(self):
        self.elements[0]['versionCode'] = 141
        self.write_metadata()
        with self.assertRaises(ValueError):
            a.validate_unsigned(self.inputs, self.root)
        self.elements[0]['versionCode'] = 140
        self.metadata['applicationId'] = 'wrong'
        self.write_metadata()
        with self.assertRaises(ValueError):
            a.validate_unsigned(self.inputs, self.root)

    def test_missing_resource(self):
        (self.root / 'core/localization/src/main/resources/localization/another').write_text('missing')
        with self.assertRaises(ValueError):
            a.validate_unsigned(self.inputs, self.root)

    def test_inspect_identity_certificate_and_version(self):
        apk = self.inputs / self.elements[0]['outputFile']
        cert = f'Number of signers: 1\nV2 Signer: certificate SHA-256 digest: {a.CERT_SHA}\n'
        alternate = f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {a.CERT_SHA}\n'
        with mock.patch.object(a, '_tool', side_effect=lambda x: Path(x)), mock.patch.object(a, '_run', side_effect=['', alternate, "package: name='com.axiel7.anihyou' versionCode='140' versionName='1.8.0'\n"]):
            a._inspect(apk, self.root)
        badge = ("package: name='com.axiel7.anihyou' versionCode='140' versionName='1.8.0' "
                 "platformBuildVersionName='17' platformBuildVersionCode='37' "
                 "compileSdkVersion='37' compileSdkVersionCodename='17'\n")
        with mock.patch.object(a, '_tool', side_effect=lambda x: Path(x)), mock.patch.object(a, '_run', side_effect=['', cert, badge]):
            a._inspect(apk, self.root)
        for bad_cert, bad_badge in [(cert.replace(a.CERT_SHA, 'a'*64), badge), (cert.replace('signers: 1', 'signers: 2'), badge), (cert.replace('Number of signers: 1\n', ''), badge), (cert, badge.replace('140', '141')), (cert, badge.replace(a.PACKAGE, 'wrong')), (cert, badge + 'application-debuggable\n')]:
            with self.subTest(bad_cert=bad_cert, bad_badge=bad_badge), mock.patch.object(a, '_tool', side_effect=lambda x: Path(x)), mock.patch.object(a, '_run', side_effect=['', bad_cert, bad_badge]), self.assertRaises(ValueError):
                a._inspect(apk, self.root)

    def test_manifest_checks_all_files_and_source_commit(self):
        output = self.root / 'output'
        output.mkdir()
        paths = []
        for name in sorted(a._names('1.8.0', False)):
            path = output / name
            path.write_bytes(name.encode())
            paths.append(path)
        with mock.patch.object(a, '_inspect'), mock.patch.object(a, '_require_clean'), mock.patch.object(a, '_source_commit', return_value='a'*40), mock.patch.object(a, '_source_tree', return_value='b'*40):
            build = a._build_record(self.inputs, self.root)
            manifest = a._manifest(paths, self.root, build)
            manifest_path = output / 'release-manifest.json'
            manifest_path.write_text(json.dumps(manifest))
            (output / 'SHA256SUMS').write_text(a._sums(paths, manifest_path))
            self.assertEqual(a.verify_artifacts(output, self.root), manifest)
            paths[0].write_bytes(b'altered')
            with self.assertRaises(ValueError):
                a.verify_artifacts(output, self.root)
            paths[0].write_bytes(paths[0].name.encode())
        with mock.patch.object(a, '_inspect'), mock.patch.object(a, '_require_clean'), mock.patch.object(a, '_source_commit', return_value='b'*40), mock.patch.object(a, '_source_tree', return_value='b'*40), self.assertRaises(ValueError):
            a.verify_artifacts(output, self.root)

    def test_build_receipt_binds_source_metadata_and_each_unsigned_apk(self):
        with mock.patch.object(a, '_require_clean'), mock.patch.object(a, '_source_commit', return_value='a'*40), mock.patch.object(a, '_source_tree', return_value='b'*40):
            receipt = a.record_build(self.inputs, self.root)
            self.assertEqual(a._read_build(self.inputs, self.root), receipt)
            receipt_path = self.inputs / a.BUILD_RECEIPT
            for field, value in [('sourceCommit', 'c'*40), ('sourceTree', 'd'*40), ('metadataSha256', '0'*64)]:
                altered = dict(receipt, **{field: value})
                receipt_path.write_text(json.dumps(altered))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    a._read_build(self.inputs, self.root)
            for index in range(5):
                altered = json.loads(json.dumps(receipt))
                altered['artifacts'][index]['sha256'] = '0'*64
                receipt_path.write_text(json.dumps(altered))
                with self.subTest(artifact=index), self.assertRaises(ValueError):
                    a._read_build(self.inputs, self.root)
            receipt_path.write_text(json.dumps(receipt))
            with mock.patch.object(a, '_source_tree', return_value='c'*40), self.assertRaises(ValueError):
                a._read_build(self.inputs, self.root)
            apk = self.inputs / receipt['artifacts'][0]['name']
            with apk.open('ab') as stream:
                stream.write(b'tampered')
            with self.assertRaises(ValueError):
                a._read_build(self.inputs, self.root)

    def test_sign_arguments_use_environment_references(self):
        calls = []
        def run(args):
            calls.append(args)
            Path(args[args.index('--out') + 1]).write_bytes(b'signed')
            return ''
        env = {'KEY_ALIAS': 'alias', 'KEYSTORE_PASSWORD': 'secret1', 'KEY_PASSWORD': 'secret2'}
        with mock.patch.dict(os.environ, env), mock.patch.object(a, '_tool', return_value=Path('apksigner')), mock.patch.object(a, '_run', side_effect=run), mock.patch.object(a, '_inspect'), mock.patch.object(a, '_source_commit', return_value='a'*40), mock.patch.object(a, '_read_build', return_value={'fixture': True}), mock.patch.object(a, 'verify_artifacts', return_value={}):
            a.sign_and_verify(self.inputs, self.root / 'output', self.root / 'keystore', self.root)
        self.assertEqual(len(calls), 5)
        for args in calls:
            self.assertIn('env:KEYSTORE_PASSWORD', args)
            self.assertIn('env:KEY_PASSWORD', args)
            self.assertEqual(args[args.index('--v4-signing-enabled') + 1], 'false')
            self.assertNotIn('secret1', args)
            self.assertNotIn('secret2', args)
        with mock.patch.dict(os.environ, env), mock.patch.object(a, '_read_build', return_value={'fixture': True}), self.assertRaises(ValueError):
            a.sign_and_verify(self.inputs, self.root / 'output', self.root / 'keystore', self.root)


if __name__ == '__main__':
    unittest.main()
