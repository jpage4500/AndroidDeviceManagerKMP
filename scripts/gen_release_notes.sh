#!/bin/bash
################################################################################
# Generate Release Notes
#
# NOTE: script depends on the following env variables (or in ~/.gradle/gradle.properties)
# - GH_TOKEN (used to view PR titles for a given commit hash)
################################################################################

USER_AGENT="Release Notes Script"
# https://github.com/jpage4500/AndroidDeviceManagerKMP/commit/HASH
REPO="AndroidDeviceManagerKMP"
FULL_REPO="jpage4500/${REPO}"

################################################################################
# FUNCTION: print release notes since the given tag
################################################################################
function logTagsSince() {
    FROM_TAG=$1
    RANGE="$FROM_TAG.."
    echo "All PRs from $FROM_TAG to NOW; $RANGE"
    COMMIT_LIST=$(git log --first-parent --max-count=100 --pretty="%h %cd %s" --date=format:'%Y-%m-%d %I:%M %p' ${RANGE})

    printList "${COMMIT_LIST}"
}

################################################################################
# FUNCTION: print out last <X> commits into $BRANCH
################################################################################
function printCommitList() {
    MAX_NUM=$1

    if [[ ${FROM_BRANCH} != "" ]]; then
        COMMIT_LIST=$(git log ${BRANCH} --first-parent --max-count=100 --pretty="%h %cd %s" --date=format:'%Y-%m-%d %I:%M %p' ${FROM_BRANCH}..${BRANCH})
    else
        COMMIT_LIST=$(git log ${BRANCH} --first-parent --max-count=${MAX_NUM} --pretty="%h %cd %s" --date=format:'%Y-%m-%d %I:%M %p')
    fi

    printList "${COMMIT_LIST}"
}

################################################################################
# FUNCTION: print out all commits into $BRANCH
################################################################################
function printLocalCommits() {
    COMMIT_LIST=$(git log ${BRANCH}...main --pretty="%h %cd %s" --date=format:'%Y-%m-%d %I:%M %p')

    printList "${COMMIT_LIST}"
}

################################################################################
# FUNCTION:
################################################################################
function printList() {
    COMMIT_LIST=$1
    IFS=$'\n'
    for LINE in ${COMMIT_LIST}; do
        HASH=$(echo ${LINE} | cut -d " " -f1)
        DATE=$(echo ${LINE} | cut -c 9-27)
        TEXT=$(echo ${LINE} | cut -c 29-)

        # check if TEXT contains "Merge pull request" - if so, get PR title and use that instead of check in comment
        if [[ ${TEXT} == *"Merge pull request"* ]]; then
            PR=$(echo ${TEXT} | grep "Merge pull request" | grep -o '\#[0-9]*' | cut -d '#' -f 2)
            # echo "$DATE - PR$PR"
            printPullRequest "${PR}"
        elif [[ $PR_ONLY == false ]]; then
            # get multi-line comments for commit; filter only lines with 3 blank spaces (hopefully always true)
            TEXT_MULTILINE=$(git show $HASH --no-patch | grep "^    " | grep -v "^$" | sed 's/    //g')
            if [[ "$NOTES_FORMAT" == "slack" ]]; then
                # slack rich media link version
                echo "<https://github.com/${FULL_REPO}/commit/$HASH|$HASH> $TEXT_MULTILINE"
            elif [[ "$NOTES_FORMAT" == "html" ]]; then
                # html link version
                echo "<a href=\"https://github.com/${FULL_REPO}/commit/$HASH\">$HASH</a> $TEXT_MULTILINE<br/>"
            elif [[ "$NOTES_FORMAT" == "brief" ]]; then
                echo "$TEXT_MULTILINE"
                # TODO: don't include link to commit
                #echo "> https://github.com/${FULL_REPO}/commit/$HASH"
                #echo
            else
                # default formatting
                echo "COMMIT:$HASH $TEXT_MULTILINE \n"
            fi
        fi
    done
}

################################################################################
# FUNCTION: print out last <X> merged PR's into $BRANCH
# NOTE: requires PR to be merged via github's interface which adds "Merge pull request" to check-in comments
################################################################################
function printPullRequestList() {
    MAX_NUM=$1

    # Get all PR's that have been merged into BRANCH
    # NOTE: requires that the PR was merged and not squashed which adds "Merge pull request" to check-in comment
    PR_LIST=$(git log ${BRANCH} --oneline --first-parent --max-count=50 | grep "Merge pull request" | grep -o '\#[0-9]*' | cut -d '#' -f 2)

    let COUNT=0
    for PRID in ${PR_LIST}; do
        printPullRequest ${PRID}
        let COUNT++
        if [[ ${COUNT} -ge ${MAX_NUM} ]]; then
            break
        fi
    done
}

################################################################################
# FUNCTION: print release notes from the most-recently-merged PR on a given ref
# NOTE: relies on GitHub merge commits ("Merge pull request #N …"), same as printPullRequestList
################################################################################
function printLastMergedPR() {
    REF=$1
    # most recent "Merge pull request #N" on REF (--first-parent = only merges into that branch)
    PRID=$(git log ${REF} --first-parent --max-count=50 --pretty="%s" 2>/dev/null \
        | grep -m1 "Merge pull request" | grep -o '\#[0-9]*' | cut -d '#' -f 2)
    if [[ -n "$PRID" ]]; then
        printPullRequest "$PRID"
    else
        echo >&2 "printLastMergedPR: no merged PR found on '$REF'"
    fi
}

################################################################################
# FUNCTION: print out PR title
################################################################################
function printPullRequest() {
    PRID=$1

    # set title of PR
    TITLE=$(getTitleForPR "${PRID}")

    if [[ "$TITLE" != "" ]]; then
        printNotes ${PRID} "${TITLE}" | grep -v "^$"
    fi
}

################################################################################
# FUNCTION: print out title and body for a given PR (empty string if PR not found or invalid)
################################################################################
function getTitleForPR() {
    PRID=$1

    CURL=$(curl -g --silent -H "Authorization: token $GH_TOKEN" \
        -H "User-Agent: $USER_AGENT" -H "Accept:application/vnd.github.v3+json" \
        https://api.github.com/repos/${FULL_REPO}/pulls/"${PRID}")

    TITLE=$(echo "$CURL" |
        python3 -c "import json,sys; obj=json.load(sys.stdin); print (obj['title']); body=(obj['body']); print (body)" |
        grep -v None)

    if [[ $? -eq 0 ]]; then
        echo "$TITLE"
    fi
}

################################################################################
# FUNCTION: print out most recent closed PR's
################################################################################
function getPRList() {
    curl -g --silent -H "Authorization: token $GH_TOKEN" -H "User-Agent: $USER_AGENT" -H "Accept:application/vnd.github.v3+json" \
        https://api.github.com/repos/${FULL_REPO}/pulls?state=closed >/tmp/prs.txt

    python3 - <<EOF
import json,sys;
with open('/tmp/prs.txt') as data_file:
    data = json.load(data_file)
    for pr in data:
        print pr['title']
        print "https://github.com/${FULL_REPO}/pull/{} ({})".format(pr['number'],pr['merged_at'])
        print
EOF
}

################################################################################
# FUNCTION: print out release notes from previously set variables
# NOTE: uses $NOTES_FORMAT to determine the format of links and newlines
################################################################################
function printNotes() {
    PRID=$1
    TITLE=$2
    BRANCH=$3

    if [[ "$NOTES_FORMAT" == "slack" ]]; then
        # slack rich media link version
        echo "<https://github.com/${FULL_REPO}/pull/$PRID|PR$PRID> $TITLE"
    elif [[ "$NOTES_FORMAT" == "html" ]]; then
        # html link version
        echo "<a href=\"https://github.com/${FULL_REPO}/pull/$PRID\">PR$PRID</a> $TITLE<br/>"
    elif [[ "$NOTES_FORMAT" == "brief" ]]; then
        echo "$TITLE"
        # TODO: don't include link to commit
        #echo "> https://github.com/${FULL_REPO}/pull/$PRID"
        echo
    else
        echo "PR$PRID $TITLE \n"
    fi
}

printUsage() {
    echo
    echo "usage: $0 <type> <format> <branch/tag>"
    echo "<type> can be one of [pr/tag/recent/lastpr/local]"
    echo " - pr = show last PR"
    echo " - recent = show last 5 PRs"
    echo " - lastpr = show the most-recently-merged PR on <branch/tag> (default origin/main)"
    echo " - tag = show release notes for all PR's from TAG until now"
    echo " - local = show all commits on this branch only"
    echo
    echo "<format> (optional) control format of html links; can be [slack/brief]"
    echo " - slack : format for sending to slack; including: html links, truncate line length"
    echo " - brief: add link below change description"
    echo " - html: html format"
    echo
    echo "examples:"
    echo "  gen_release_notes.sh recent brief"
    echo "  gen_release_notes.sh tag brief 1053"
    echo "  gen_release_notes.sh pr brief"
    echo "  gen_release_notes.sh local"
}

function getProperty {
    PROP_KEY=$1
    PROP_VALUE=$(cat ~/.gradle/gradle.properties | grep "^$PROP_KEY=" | cut -d'=' -f2-)
    echo ${PROP_VALUE}
}

################################################################################
# script start
################################################################################

# NOTES_TYPE:
#       - "pr" = show last PR
#       - "" = show last PR
NOTES_TYPE=$1

# NOTES_FORMAT:
#       - "slack" = add html links to release notes for slack messages
NOTES_FORMAT=$2

# ARG1
ARG1=$3

cd "$(dirname $0)"
# change from /scripts to root directory
cd ..

# require python
type python3 >/dev/null 2>&1 || {
    echo >&2 "Python not installed."
    exit 1
}

# check for GH_TOKEN env variable
if [[ ${GH_TOKEN} == "" ]]; then
    # try reading it locally
    GH_TOKEN=$(getProperty "GH_TOKEN")
    if [[ ${GH_TOKEN} == "" ]]; then
        echo >&2 "GH_TOKEN environment var not set!"
        exit 1
    fi
fi

# set BRANCH to current branch
BRANCH=$(git rev-parse --abbrev-ref HEAD)
#BRANCH=$(git branch | grep -e "^*" | cut -d' ' -f 2)

PR_ONLY=false

if [[ "$NOTES_TYPE" == "pr" ]]; then
    printCommitList 1
elif [[ "$NOTES_TYPE" == "recent" ]]; then
    PR_ONLY=true
    printPullRequestList 5
elif [[ "$NOTES_TYPE" == "lastpr" ]]; then
    PR_ONLY=true
    # notes from the most-recently-merged PR on ARG1 (default origin/main); used by the
    # locally-run iOS release, which builds off a feature branch after the release PR merged
    printLastMergedPR "${ARG1:-origin/main}"
elif [[ "$NOTES_TYPE" == "commit" ]]; then
    printCommitList 5
elif [[ "$NOTES_TYPE" == "tag" ]]; then
    PR_ONLY=true
    logTagsSince $ARG1
elif [[ "$NOTES_TYPE" == "local" ]]; then
    NOTES_FORMAT="brief"
    printLocalCommits
else
    echo ""
    echo "invalid argument: $NOTES_TYPE"
    printUsage
fi
