#!/usr/bin/env bash
# Resets the wallet store for a clean demo take: backs up data/wallet.store to data/backups/ and removes it,
# which wipes all credentials, holder keys and approvals. API keys, the store key and the config are kept.
# Stop the wallet first (the tool refuses while it is listening), then start it again afterwards.
#   usage: scripts/reset.sh [config/wallet.json]      (run from wallet/; needs ./gradlew installDist)
set -euo pipefail
cd "$(dirname "$0")/.."
# JDK 21 is required. A2WP_JAVA_HOME wins, then the dev Mac's Zulu 21, then whatever JAVA_HOME is set.
ZULU21=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home
if [ -n "${A2WP_JAVA_HOME:-}" ]; then export JAVA_HOME="$A2WP_JAVA_HOME"
elif [ -d "$ZULU21" ]; then export JAVA_HOME="$ZULU21"
fi
exec build/install/a2wp-kt/bin/a2wpctl reset "${1:-config/wallet.json}"
