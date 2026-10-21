# Common-password list (password policy P7)

*Copyright (c) 2026 FLYT. Author: Pythagore.*

`common-passwords.txt` is third-party data, derived from SecLists
(MIT License, Copyright (c) 2018 Daniel Miessler):

| Item | Value |
| --- | --- |
| Source | `Passwords/Common-Credentials/Pwdb_top-1000000.txt` |
| Commit | `913b327317496d062bcc7cace524aaad8a693be2` |
| Source SHA-256 | `e9a88f67aafe65496682dc374559ee714e978bee50314767494c3e37a18c9fc8` |
| Result SHA-256 | `01e70dd6c848616ec19ed6bbb6461133d2131fd80d7755a85e9800ed24d1b630` |
| Entries | 29982 |

Only entries the policy would otherwise accept are kept (12 to 128 characters), in lower
case, without duplicates, in their original frequency order:

```sh
tr -d '\r' < Pwdb_top-1000000.txt | awk 'length($0)>=12 && length($0)<=128' \
  | tr 'A-Z' 'a-z' | awk '!seen[$0]++' > common-passwords.txt
```

`CommonPasswordListTest` checks the result SHA-256, so the file cannot change unnoticed.
To refresh it, run the command on a newer commit and update this table and the test.
