# Third-party notices

| Component | Where | Source | License |
| --- | --- | --- | --- |
| Shizuku API client (`api`, `provider`, `shared`, `aidl`) | `libs/`, `tools/original-libs/` | https://github.com/RikkaApps/Shizuku-API | Apache-2.0 |

`libs/api-desugared.jar` is Shizuku's `api.jar` with its Java lambdas rewritten by
[Retrolambda](https://github.com/luontola/retrolambda) (Apache-2.0); see `tools/desugar-shizuku.sh`.
The jars were taken from the Duo Fold Live build; `tools/original-libs/ORIGINAL-SOURCE.md` records
their SHA-256 hashes.

## Acknowledgements

N6 Fold Duo is inspired by [Duo Fold Live](https://github.com/joeconsorti/duo-fold-live)
(MIT License, Copyright (c) 2026 Duo Fold Live contributors), an iPhone Duo-style fold animation
for the Samsung Galaxy Z Fold. Its approach of capturing the display through a Shizuku helper while
excluding the app's own overlay layer informed `ShellService`. N6 Fold Duo is a separate
implementation for the OPPO Find N6.
