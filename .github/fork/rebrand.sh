#!/usr/bin/env bash
# Rebrands the checkout as the fork's app ("Phoebe+") before a CI build so it installs and
# runs alongside upstream Phoebe. Only used by .github/workflows/fork-release.yml; nothing
# here is committed to source, which keeps PRs to upstream clean.
#
# Every replacement must match, so if upstream reworks one of these lines the build fails
# loudly instead of quietly shipping an app that collides with upstream's.
#
# Never change FORK_APPLICATION_ID, FORK_UPGRADE_UUID or FORK_DATA_DIR once people have
# installed a release: updates would stop installing over the old app or lose its data.
set -euo pipefail

: "${FORK_APP_NAME:?}" "${FORK_APPLICATION_ID:?}" "${FORK_UPGRADE_UUID:?}" "${FORK_DATA_DIR:?}" "${FORK_GITHUB_OWNER:?}"

replace() {
  local file="$1" old="$2" new="$3"
  if ! grep -qF -- "${old}" "${file}"; then
    echo "::error file=${file}::Fork rebrand: expected text not found (did upstream change it?): ${old}"
    exit 1
  fi
  OLD="${old}" NEW="${new}" perl -pi -e 's/\Q$ENV{OLD}\E/$ENV{NEW}/g' "${file}"
  echo "${file}: ${old} -> ${new}"
}

# Android: own package id and launcher label.
replace androidApp/build.gradle.kts \
  'applicationId = "com.phoebe.app"' \
  "applicationId = \"${FORK_APPLICATION_ID}\""
mkdir -p androidApp/src/release/res/values
cat > androidApp/src/release/res/values/strings.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${FORK_APP_NAME}</string>
</resources>
EOF

# Desktop: app name, macOS bundle id, Windows MSI upgrade code.
replace composeApp/build.gradle.kts \
  'else "Phoebe"' \
  "else \"${FORK_APP_NAME}\""
replace composeApp/build.gradle.kts \
  'else "com.joetr.phoebe"' \
  "else \"${FORK_APPLICATION_ID}\""
replace composeApp/build.gradle.kts \
  '"2C1E8421-2A6D-42AA-8C59-C82BE282E92F"' \
  "\"${FORK_UPGRADE_UUID}\""

# Desktop: own data folder under the home directory instead of upstream's ~/.phoebe.
replace core/platform/src/commonMain/kotlin/com/phoebe/app/platform/StorageNames.kt \
  'else ".phoebe"' \
  "else \"${FORK_DATA_DIR}\""

# Telemetry: the Sentry DSN is upstream's project, so fork builds must not report to it.
# An empty DSN makes the Sentry SDK disable itself on every platform.
replace core/platform/src/commonMain/kotlin/com/phoebe/app/telemetry/Telemetry.kt \
  '"https://86de8cb5898a6e529bb04cef2cad8677@o4511214016200704.ingest.us.sentry.io/4511412309524480"' \
  '""'

# In-app update check: look at this fork's releases, not upstream's.
replace core/platform/build.gradle.kts \
  '"j-roskopf"' \
  "\"${FORK_GITHUB_OWNER}\""
