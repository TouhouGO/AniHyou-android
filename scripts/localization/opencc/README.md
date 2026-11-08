# Search folding data

`TSCharacters.txt` is OpenCC's complete character dictionary at revision
`556ed22496d650bd0b13b6c163be9814637970ae`, SHA-256
`6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a`.
Source: https://github.com/BYVoid/OpenCC/blob/556ed22496d650bd0b13b6c163be9814637970ae/data/dictionary/TSCharacters.txt
OpenCC is distributed under Apache License 2.0; see `LICENSE`.

Run `python3 scripts/localization/opencc/generate.py` to regenerate the sorted
big-endian code point pairs. Add `--check` to verify the committed resource.
The generator chooses the first target, resolves chains and rejects cycles.
This is a lossy search fold, not a display conversion or a phrase converter.

The search helper uses fixed CJK block ranges based on Unicode 15.1's
[Blocks.txt](https://www.unicode.org/Public/15.1.0/ucd/Blocks.txt), along with
specific Han numerals and marks from [Scripts.txt](https://www.unicode.org/Public/15.1.0/ucd/Scripts.txt)
and the runtime's Han script classification.
The block ranges include unassigned code points and are not an exact enumeration
of `Script=Han`; they ensure every generated mapping key and target is handled
even on systems with older Unicode tables. For normalization, only fixed
ideograph blocks bypass the runtime letter/digit filter; punctuation keeps its
prior handling. Other characters and NFKC still use runtime Unicode data.
