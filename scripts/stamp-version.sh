#!/usr/bin/env bash
#
# Stamp the project version, in the one place that defines it.
#
#   YY.M.D.BUCKET
#
#     YY.M.D   the release date, no zero padding. What a person reads.
#     BUCKET   H * 10 + M / 10, the ten-minute bucket of the UTC time
#              the build was cut in, so two builds on one day always
#              increase. UTC, and stated rather than inherited from the
#              machine: a build cut in Helsinki, in a CI runner, or on
#              a laptop somewhere else must number the same, and a
#              version that means different instants in different
#              places is not a version.
#
# The same scheme every RefineID project uses; Apple splits it into two
# settings because Apple wants two numbers, and Maven takes one string,
# so here the bucket is the fourth component.
#
# Nothing else in the file is touched, and a stamp that matched nothing
# fails rather than passing silently -- a version that quietly did not
# change is found at the next release, by which time it is a puzzle.

set -euo pipefail
cd "$(dirname "$0")/.."

read -r yy mm dd hh mn <<<"$(TZ=UTC date '+%y %m %d %H %M')"
version="${yy}.$((10#$mm)).$((10#$dd)).$((10#$hh * 10 + 10#$mn / 10))"

case "${1:-}" in
  --dry-run)
    echo "would stamp ${version}"
    exit 0
    ;;
  "") ;;
  *)
    echo "unknown argument: ${1}" >&2
    exit 2
    ;;
esac

# The project version is the first <version> in the file, the one that
# belongs to this artifact rather than to a dependency.
/usr/bin/sed -i '' -E "1,/<\/version>/ s|(<version>)[^<]*(</version>)|\1${version}\2|" pom.xml

grep -q "<version>${version}</version>" pom.xml || {
  echo "stamping changed nothing; pom.xml is not shaped as expected" >&2
  exit 1
}

echo "stamped ${version}"
