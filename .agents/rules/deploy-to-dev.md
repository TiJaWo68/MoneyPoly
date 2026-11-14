---
trigger: always_on
---

# Automatic Deployment to Test System (`moneypoly-dev`)

After completing the implementation of a GitHub issue (feature or bug fix) and verifying that the full test suite (`mvn clean test`) passes without errors:

1. Check whether `build_and_deploy2dev.cmd` exists in the workspace root (`c:\Users\tillw\eclipse-workspace\MoneyPoly`).
2. If it exists, automatically run `.\build_and_deploy2dev.cmd` (with `BypassSandbox: true`, since it builds the production JAR and deploys via `scp`/`ssh`) to update the test system before reporting completion to the user.
3. Do not commit or push autonomously; deployment to `moneypoly-dev` does not replace the user's review and final commit approval.
