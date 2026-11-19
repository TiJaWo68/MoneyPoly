#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

PROD_HOST="${MONEYPOLY_PROD_HOST:-moneypoly}"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "ERROR: Working tree is not clean. Commit or stash all changes before deploying to production." >&2
    exit 1
fi

VERSION=$(python3 -c '
import xml.etree.ElementTree as ET
tree = ET.parse("pom.xml")
root = tree.getroot()
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
el = root.find("m:version", ns)
print(el.text.strip() if el is not None else "")
')
if [[ -z "${VERSION}" ]]; then
    echo "ERROR: Could not determine project version from pom.xml." >&2
    exit 1
fi

TAG="v${VERSION}"
if ! git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
    echo "==> Creating Git tag ${TAG} ..."
    git tag -a "${TAG}" -m "Release ${TAG}"
else
    TAG_COMMIT=$(git rev-list -n 1 "${TAG}")
    HEAD_COMMIT=$(git rev-parse HEAD)
    if [[ "${TAG_COMMIT}" != "${HEAD_COMMIT}" ]]; then
        echo "ERROR: Tag ${TAG} already exists on ${TAG_COMMIT}, which differs from HEAD (${HEAD_COMMIT}). Bump version first." >&2
        exit 1
    fi
    echo "==> Git tag ${TAG} already points to HEAD."
fi

echo "==> Pushing tag ${TAG} to origin ..."
git push origin "${TAG}"

echo "==> Building MoneyPoly production JAR (${VERSION})..."
mvn clean package -Pproduction -DskipTests

JAR_FILE="target/moneypoly-${VERSION}.jar"
if [[ ! -f "${JAR_FILE}" ]]; then
    JAR_FILE=$(ls -t target/moneypoly-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
fi
if [[ -z "${JAR_FILE}" || ! -f "${JAR_FILE}" ]]; then
    echo "ERROR: No built JAR found in target/moneypoly-*.jar" >&2
    exit 1
fi

echo "==> Creating pre-deploy PostgreSQL backup on ${PROD_HOST} ..."
ssh "root@${PROD_HOST}" 'mkdir -p /var/backups/moneypoly && su - postgres -c "pg_dump --clean --if-exists moneypoly" | gzip > "/var/backups/moneypoly/predeploy-$(date +%Y%m%d-%H%M%S).sql.gz"'

echo "==> Deploying ${JAR_FILE} to root@${PROD_HOST}:/opt/moneypoly/moneypoly.jar ..."
scp "${JAR_FILE}" "root@${PROD_HOST}:/opt/moneypoly/moneypoly.jar"

echo "==> Restarting moneypoly service on ${PROD_HOST}..."
ssh "root@${PROD_HOST}" "systemctl restart moneypoly && systemctl is-active moneypoly"

echo "==> Production deployment of ${TAG} to ${PROD_HOST} completed successfully."
