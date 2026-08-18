#!/bin/sh
set -e
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
VERSION=3.9.11
MAVEN_HOME="$BASE_DIR/.mvn/apache-maven-$VERSION"
if [ ! -x "$MAVEN_HOME/bin/mvn" ]; then
  URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/$VERSION/apache-maven-$VERSION-bin.tar.gz"
  mkdir -p "$BASE_DIR/.mvn"
  curl -fsSL "$URL" | tar -xz -C "$BASE_DIR/.mvn"
fi
exec "$MAVEN_HOME/bin/mvn" "$@"
