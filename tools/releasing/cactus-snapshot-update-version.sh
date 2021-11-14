#!/bin/bash

#///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
#
#  © 2011-2021 Telenav, Inc.
#  Licensed under Apache License, Version 2.0
#
#///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

source library-functions.sh
source cactus-projects.sh

version="${1%-SNAPSHOT}-SNAPSHOT"

help="[version]"

require_variable version "$help"

for project_home in "${CACTUS_PROJECT_HOMES[@]}"; do

    update_version "$project_home" "$version"

done
