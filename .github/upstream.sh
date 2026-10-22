#!/usr/bin/env bash
# Moves the libraries pinned in build.xml to upstream's newest releases:
# iText 5, BouncyCastle, and JGoodies from Maven Central, and MuPDF from
# mupdf.com. Each newer version gets the SHA-256 checksums of its downloads,
# which are first checked against the checksums that upstream publishes, and
# a line in the ChangeLog section of README.md. Prints one line per update,
# and nothing when every pin is current. Runs from the repository root.
set -euo pipefail
# Without it, a failed checksum check inside $(...) would go unnoticed
shopt -s inherit_errexit

MAVEN=https://repo1.maven.org/maven2
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

prop() {
	sed -n "s/.*<property name=\"$1\" value=\"\([^\"]*\)\".*/\1/p" build.xml
}

setprop() {
	sed -i "s|\(<property name=\"$1\" value=\"\)[^\"]*\"|\1$2\"|" build.xml
}

# Whether version $2 is newer than version $1
newer() {
	[ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n 1)" = "$2" ]
}

# The newest release of a Maven artefact, leaving out versions with a qualifier such as -RC1
maven_latest() {
	curl -fsSL "$MAVEN/$1/maven-metadata.xml" | grep -o '<version>[0-9.]*</version>' |
		sed 's/<[^>]*>//g' | sort -V | tail -n 1
}

# The SHA-256 of a file on Maven Central, after checking the download against the SHA-1 beside it
maven_sha256() {
	curl -fsSL -o "$work/download" "$MAVEN/$1"
	echo "$(curl -fsSL "$MAVEN/$1.sha1" | cut -c 1-40)  $work/download" | sha1sum --check --quiet -
	sha256sum "$work/download" | cut -d ' ' -f 1
}

# Puts "- Update NAME to VERSION" first in the ChangeLog's unreleased section, which it starts if
# there is none, and drops an earlier such line for NAME from that section
changelog() {
	awk -v line="- Update $1 to $2" -v prefix="- Update $1 to " '
		done { print; next }
		/^## ChangeLog/ { changelog = 1; print; next }
		changelog && !unreleased && /^### / {
			if ($0 !~ /^### Unreleased/) {
				print "### Unreleased"; print ""; print line; print ""; print
				done = 1
				next
			}
			unreleased = 1
			print; print ""; print line
			getline
			if ($0 != "") print
			next
		}
		unreleased && /^### / { done = 1; print; next }
		unreleased && index($0, prefix) == 1 && substr($0, length(prefix) + 1) ~ /^[0-9.]+$/ { next }
		{ print }
	' README.md > "$work/README.md"
	cp "$work/README.md" README.md
}

# maven NAME VERSION_PROPERTY GROUP_PATH ARTIFACT[:CLASSIFIER]=SHA256_PROPERTY...
# The first artefact decides the newest version, which the others must also have.
maven() {
	local name=$1 property=$2 group=$3
	shift 3
	local old new pin artifact classifier sum
	local sums=()
	old=$(prop "$property")
	new=$(maven_latest "$group/${1%%[:=]*}")
	newer "$old" "$new" || return 0
	for pin in "$@"; do
		artifact=${pin%%=*}
		classifier=
		if [[ $artifact == *:* ]]; then
			classifier=-${artifact#*:}
			artifact=${artifact%%:*}
		fi
		sums+=("${pin#*=}=$(maven_sha256 "$group/$artifact/$new/$artifact-$new$classifier.jar")")
	done
	setprop "$property" "$new"
	for sum in "${sums[@]}"; do
		setprop "${sum%%=*}" "${sum#*=}"
	done
	changelog "$name" "$new"
	echo "Update $name from $old to $new"
}

# The page data of mupdf.com/releases lists every release file with its SHA-256
mupdf() {
	local old new sum
	old=$(prop MUPDF_VERSION)
	curl -fsSL https://mupdf.com/releases > "$work/releases.html"
	new=$(grep -o 'mupdf-[0-9.]*-source\.tar\.gz' "$work/releases.html" |
		sed 's/^mupdf-//; s/-source\.tar\.gz$//' | sort -V | tail -n 1)
	newer "$old" "$new" || return 0
	curl -fsSL -o "$work/download" "https://mupdf.com/downloads/archive/mupdf-$new-source.tar.gz"
	sum=$(sha256sum "$work/download" | cut -d ' ' -f 1)
	if ! grep -q "\"File\":\"mupdf-$new-source\.tar\.gz\",[^}]*\"SHA256\":\"$sum\"" "$work/releases.html"; then
		echo "mupdf-$new-source.tar.gz does not have the SHA-256 that mupdf.com lists" >&2
		exit 1
	fi
	setprop MUPDF_VERSION "$new"
	setprop MUPDF_SHA256 "$sum"
	changelog MuPDF "$new"
	echo "Update MuPDF from $old to $new"
}

maven iText ITEXT_VERSION com/itextpdf itextpdf=ITEXT_SHA256 itextpdf:sources=ITEXT_SOURCES_SHA256
maven BouncyCastle BC_VERSION org/bouncycastle \
	bcprov-jdk18on=BCPROV_SHA256 bcpkix-jdk18on=BCPKIX_SHA256 bcutil-jdk18on=BCUTIL_SHA256
maven "JGoodies Forms" FORMS_VERSION com/jgoodies jgoodies-forms=FORMS_SHA256
maven "JGoodies Common" COMMON_VERSION com/jgoodies jgoodies-common=COMMON_SHA256
mupdf
