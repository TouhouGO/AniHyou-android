#!/usr/bin/env node
import fs from 'node:fs';
import readline from 'node:readline';

function parseArgs() {
  const args = process.argv.slice(2);
  const result = {};
  for (let i = 0; i < args.length; i++) {
    if (args[i].startsWith('--')) {
      const key = args[i].slice(2);
      const val = args[i + 1];
      result[key] = val;
      i++;
    }
  }
  if (!result.titles || !result.archive || !result.output) {
    console.error('Usage: node enrich-titles-from-bangumi-archive.mjs --titles <titles.json> --archive <subject.jsonlines> --output <output.json> [--media-types <media_types.json>] [--report <report.json>]');
    process.exit(1);
  }
  return result;
}

async function main() {
  const { titles: titlesPath, archive: archivePath, output: outputPath, 'media-types': mediaTypesPath, report: reportPath } = parseArgs();

  // 1. Read input titles
  const rawTitles = fs.readFileSync(titlesPath, 'utf8');
  const titles = JSON.parse(rawTitles);
  const inputCount = Object.keys(titles).length;

  let externalMediaTypes = {};
  if (mediaTypesPath && fs.existsSync(mediaTypesPath)) {
    try {
      externalMediaTypes = JSON.parse(fs.readFileSync(mediaTypesPath, 'utf8'));
    } catch (err) {
      console.warn('Warning: Could not parse media-types file:', err.message);
    }
  }

  // 2. Index AniList ID -> Bangumi ID mapping and Media Type
  // Expected mapping contract:
  // - AniList ANIME -> Bangumi type 2 (动画)
  // - AniList MANGA -> Bangumi type 1 (书籍)
  const bangumiToEntries = new Map(); // bgmId -> Array<{ anilistId: string, expectedType: 'ANIME' | 'MANGA' }>

  for (const [anilistId, rawVal] of Object.entries(titles)) {
    if (typeof rawVal === 'string') {
      const parts = rawVal.split('|');
      let bgmId = null;
      let expectedType = 'ANIME'; // default to ANIME

      if (externalMediaTypes[anilistId]) {
        const extType = String(externalMediaTypes[anilistId]).trim().toUpperCase();
        if (extType === 'MANGA' || extType === 'BOOK' || extType === '1') {
          expectedType = 'MANGA';
        } else {
          expectedType = 'ANIME';
        }
      }

      if (parts.length >= 3) {
        // Form: "Title|BgmId|Type"
        const lastPart = parts[parts.length - 1].trim().toUpperCase();
        if (lastPart === 'MANGA' || lastPart === 'BOOK' || lastPart === '1') {
          expectedType = 'MANGA';
          bgmId = parseInt(parts[parts.length - 2].trim(), 10);
        } else if (lastPart === 'ANIME' || lastPart === '2') {
          expectedType = 'ANIME';
          bgmId = parseInt(parts[parts.length - 2].trim(), 10);
        } else {
          bgmId = parseInt(parts[parts.length - 1].trim(), 10);
        }
      } else if (parts.length === 2) {
        // Form: "Title|BgmId"
        bgmId = parseInt(parts[1].trim(), 10);
      }

      if (bgmId !== null && !isNaN(bgmId) && bgmId > 0) {
        if (!bangumiToEntries.has(bgmId)) {
          bangumiToEntries.set(bgmId, []);
        }
        bangumiToEntries.get(bgmId).push({
          anilistId,
          expectedType,
        });
      }
    }
  }

  console.log(`Indexed ${bangumiToEntries.size} unique Bangumi IDs from ${inputCount} titles.`);

  // 3. Scan Bangumi archive: collect candidate subjects
  // Schema: { id: number, type: number, name: string, name_cn: string, ... }
  // Bangumi subject types: 1 = 书籍 (Book/Manga), 2 = 动画 (Anime), 3 = 音乐, 4 = 游戏, 6 = 三次元
  const candidateMap = new Map(); // bgmId -> { count: number, type: number, nameCn: string, conflict: boolean }
  let invalidLineCount = 0;

  const fileStream = fs.createReadStream(archivePath, { encoding: 'utf8' });
  const rl = readline.createInterface({
    input: fileStream,
    crlfDelay: Infinity,
  });

  for await (const line of rl) {
    if (!line || line.trim() === '') continue;
    let subject;
    try {
      subject = JSON.parse(line);
    } catch {
      invalidLineCount++;
      continue;
    }

    const bgmId = subject.id;
    if (bangumiToEntries.has(bgmId)) {
      const type = subject.type;
      const nameCn = (subject.name_cn || '').trim();

      const existing = candidateMap.get(bgmId);
      if (!existing) {
        candidateMap.set(bgmId, {
          count: 1,
          type: type,
          nameCn: nameCn,
          conflict: false,
        });
      } else {
        existing.count++;
        // If multiple entries have conflicting name_cn or type, mark conflict
        if (existing.nameCn !== nameCn || existing.type !== type) {
          existing.conflict = true;
        }
      }
    }
  }

  // 4. Enrich titles according to strict type & identity contract
  let replacedCount = 0;
  let matchedCount = 0;
  let missingCount = 0;
  let typeMismatchCount = 0;
  let duplicateCount = 0;

  for (const [bgmId, entries] of bangumiToEntries.entries()) {
    const candidate = candidateMap.get(bgmId);
    if (!candidate) {
      missingCount += entries.length;
      continue;
    }
    matchedCount += entries.length;

    if (candidate.conflict || candidate.count > 1) {
      duplicateCount += entries.length;
      // Do not replace ambiguous/conflicting archive entries
      continue;
    }

    for (const entry of entries) {
      const { anilistId, expectedType } = entry;

      // Strict type check:
      // AniList ANIME -> Bangumi type 2 (动画)
      // AniList MANGA -> Bangumi type 1 (书籍)
      const isTypeCompatible =
        (expectedType === 'ANIME' && candidate.type === 2) ||
        (expectedType === 'MANGA' && candidate.type === 1);

      if (!isTypeCompatible) {
        typeMismatchCount++;
        // Retain original title safely on type mismatch
        continue;
      }

      if (candidate.nameCn && candidate.nameCn.length > 0) {
        const oldVal = titles[anilistId];
        const firstPipe = oldVal.indexOf('|');
        const suffix = firstPipe !== -1 ? oldVal.substring(firstPipe) : `|${bgmId}`;
        const newVal = `${candidate.nameCn}${suffix}`;
        if (oldVal !== newVal) {
          titles[anilistId] = newVal;
          replacedCount++;
        }
      }
    }
  }

  console.log(`Title calibration summary:
    Input: ${inputCount}
    Matched: ${matchedCount}
    Replaced: ${replacedCount}
    Missing: ${missingCount}
    Type Mismatch: ${typeMismatchCount}
    Duplicate / Conflict: ${duplicateCount}
    Invalid Archive Lines: ${invalidLineCount}`);

  // 5. Atomically write to output using a temporary file
  const tmpOutput = `${outputPath}.tmp.${Date.now()}`;
  fs.writeFileSync(tmpOutput, JSON.stringify(titles, null, 2), 'utf8');
  fs.renameSync(tmpOutput, outputPath);
  console.log(`Successfully written calibrated titles to: ${outputPath}`);

  // 6. Write report if requested
  if (reportPath) {
    const reportData = {
      input: inputCount,
      matched: matchedCount,
      replaced: replacedCount,
      missing: missingCount,
      typeMismatch: typeMismatchCount,
      duplicate: duplicateCount,
      invalidLine: invalidLineCount,
      timestamp: new Date().toISOString(),
    };
    fs.writeFileSync(reportPath, JSON.stringify(reportData, null, 2), 'utf8');
    console.log(`Successfully written report to: ${reportPath}`);
  }
}

main().catch((err) => {
  console.error('Fatal error during title enrichment:', err);
  process.exit(1);
});
