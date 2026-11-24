#!/usr/bin/env bash
# Builds the macOS DMG for a fork release on this Mac and attaches it to that GitHub
# release. Use it when the Fork Release workflow's macOS job fails, which mostly happens
# because GitHub has no macOS runner free.
#
# It builds what CI would have built: the release's own commit, rebranded by rebrand.sh,
# with the same version name and version code. It builds in a temporary git worktree, so
# your checkout and branch are left alone.
#
# Usage: .github/fork/build-macos-release.sh [tag] [options]
#   tag                 release tag, e.g. v2026.1124.1603 (default: the latest release)
#   --no-upload         build only; the DMG is left in build/fork-release/
#   --force             build even if the release already has a macOS DMG
#   --keep              keep the temporary worktree (prints its path)
#   --version-code N    use N instead of looking up the code CI computed
#
# Needs: Apple Silicon Mac, gh (logged in), Homebrew, JDK 21. Installs cmake and glm
# with Homebrew if they are missing. Set PHOEBE_GOOGLE_MAPS_DESKTOP_API_KEY to include
# the maps key, as CI does; without it map views are blank.
set -euo pipefail

REPO="jpage4500/Phoebe"
WORKFLOW="fork-release.yml"
ARTIFACT_SUFFIX="macos-arm64.dmg"

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

tag=""
upload=1
force=0
keep=0
version_code=""
while (($#)); do
  case "$1" in
    --no-upload) upload=0 ;;
    --force) force=1 ;;
    --keep) keep=1 ;;
    --version-code) shift; version_code="${1:-}"; [[ "${version_code}" =~ ^[0-9]+$ ]] || die "--version-code needs a number" ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [[ -z "${tag}" ]] || die "only one tag allowed"; tag="$1" ;;
  esac
  shift
done

[[ "$(uname -s)-$(uname -m)" == "Darwin-arm64" ]] || die "needs an Apple Silicon Mac (CI publishes a macos-arm64 DMG)"
command -v gh > /dev/null || die "gh is not installed"
command -v brew > /dev/null || die "Homebrew is not installed"
gh auth status > /dev/null 2>&1 || die "gh is not logged in (run: gh auth login)"
JAVA_HOME="$(/usr/libexec/java_home -v 21 2> /dev/null)" || die "JDK 21 not found"
export JAVA_HOME

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
OUT_DIR="${ROOT}/build/fork-release"

# --- Release ---------------------------------------------------------------------------
step "Looking up release ${tag:-(latest)}"
release_json="$(gh release view ${tag:+"${tag}"} --repo "${REPO}" --json tagName,targetCommitish,publishedAt,assets)" \
  || die "release ${tag:-(latest)} not found in ${REPO}"
tag="$(jq -r .tagName <<< "${release_json}")"
target="$(jq -r .targetCommitish <<< "${release_json}")"
published_at="$(jq -r .publishedAt <<< "${release_json}")"
existing_dmg="$(jq -r --arg s "${ARTIFACT_SUFFIX}" '.assets[].name | select(endswith($s))' <<< "${release_json}")"
version="${tag#v}"
[[ "${version}" =~ ^[0-9]{4}\.[0-9]{4}\.[0-9]{4}$ ]] || die "unexpected tag format: ${tag}"
echo "Tag ${tag}, commit ${target}"

if [[ -n "${existing_dmg}" && "${force}" -eq 0 && "${upload}" -eq 1 ]]; then
  die "${tag} already has ${existing_dmg}; use --force to rebuild and replace it"
fi

# --- Version code: the one CI's "Compute version" job logged for this release -----------
if [[ -z "${version_code}" ]]; then
  step "Finding the version code CI used"
  pattern="Version ${version//./\\.} \\(code [0-9]+\\)"
  run_ids="$(gh run list --repo "${REPO}" --workflow "${WORKFLOW}" --limit 50 \
    --json databaseId,conclusion,createdAt \
    --jq ".[] | select(.conclusion != \"skipped\" and .createdAt <= \"${published_at}\") | .databaseId")"
  for run_id in ${run_ids}; do
    job_id="$(gh run view "${run_id}" --repo "${REPO}" --json jobs \
      --jq '.jobs[] | select(.name == "Compute version") | .databaseId' 2> /dev/null || true)"
    [[ -n "${job_id}" ]] || continue
    line="$(gh run view --repo "${REPO}" --job "${job_id}" --log 2> /dev/null | grep -oE "${pattern}" | head -1 || true)"
    if [[ -n "${line}" ]]; then
      version_code="$(grep -oE '[0-9]+\)$' <<< "${line}" | tr -d ')')"
      echo "Run ${run_id}: ${line}"
      break
    fi
  done
  [[ -n "${version_code}" ]] || die "could not find the CI run for ${tag}; pass --version-code N"
fi

# --- Build tools ------------------------------------------------------------------------
for formula in cmake glm; do
  if ! brew list --versions "${formula}" > /dev/null 2>&1; then
    step "Installing ${formula} with Homebrew"
    brew install "${formula}"
  fi
done

# --- Worktree at the release commit -----------------------------------------------------
step "Checking out ${target} in a temporary worktree"
git -C "${ROOT}" fetch --quiet origin
if ! git -C "${ROOT}" rev-parse --verify --quiet "${target}^{commit}" > /dev/null; then
  git -C "${ROOT}" fetch --quiet origin "${target}" || die "cannot fetch ${target}"
fi
work_parent="$(mktemp -d "${TMPDIR:-/tmp}/phoebe-macos-release.XXXXXX")"
work="${work_parent}/src"
cleanup() {
  if [[ "${keep}" -eq 1 ]]; then
    echo "Kept worktree: ${work}"
    return
  fi
  git -C "${ROOT}" worktree remove --force "${work}" > /dev/null 2>&1 || true
  rm -rf "${work_parent}"
}
trap cleanup EXIT
git -C "${ROOT}" worktree add --quiet --detach "${work}" "${target}"
# Gradle may need sdk.dir to configure the Android modules.
[[ -f "${ROOT}/local.properties" ]] && cp "${ROOT}/local.properties" "${work}/local.properties"
cd "${work}"

# --- Same steps as the workflow's macOS job ---------------------------------------------
step "Rebranding as the fork app"
workflow_file=".github/workflows/${WORKFLOW}"
for var in FORK_APP_NAME FORK_APPLICATION_ID FORK_UPGRADE_UUID FORK_DATA_DIR; do
  value="$(sed -n "s/^  ${var}: *//p" "${workflow_file}" | head -1)"
  [[ -n "${value}" ]] || die "${var} not found in ${workflow_file}"
  export "${var}=${value}"
done
export FORK_GITHUB_OWNER="${REPO%%/*}"
./.github/fork/rebrand.sh

step "Building projectM"
./scripts/build-projectm.sh macos-arm64

step "Building DMG ${version} (code ${version_code})"
./gradlew --no-daemon \
  :composeApp:packageReleaseDmg \
  -Pphoebe.versionName="${version}" \
  -Pphoebe.versionCode="${version_code}"

dmg="$(find composeApp/build -name '*.dmg' -print -quit)"
[[ -n "${dmg}" ]] || die "could not find the DMG under composeApp/build"
mkdir -p "${OUT_DIR}"
out="${OUT_DIR}/PhoebePlus-${version}-${ARTIFACT_SUFFIX}"
cp "${dmg}" "${out}"
echo "DMG: ${out}"

# --- Attach to the release --------------------------------------------------------------
if [[ "${upload}" -eq 0 ]]; then
  echo "Not uploading (--no-upload)."
  exit 0
fi

step "Uploading to ${tag}"
gh release upload "${tag}" "${out}" --repo "${REPO}" --clobber

# Notes say e.g. "Builds: Android: success, macOS: failure, ..."; record the local build.
notes_file="${work_parent}/notes.md"
gh release view "${tag}" --repo "${REPO}" --json body --jq .body \
  | sed -E '/^Builds:/ s/macOS: [a-z_ ()]+(,|$)/macOS: success (built locally)\1/' > "${notes_file}"
gh release edit "${tag}" --repo "${REPO}" --notes-file "${notes_file}" > /dev/null

echo
echo "Done: https://github.com/${REPO}/releases/tag/${tag}"
