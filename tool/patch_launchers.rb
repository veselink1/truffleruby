PRELUDE = <<BASH
#!/usr/bin/env bash
#
# This file was generated by RubyGems.
# The above lines match the format expected by rubygems/installer.rb check_executable_overwrite
# bash section ignored by the Ruby interpreter

# get the absolute path of the executable and resolve symlinks
SELF_PATH=$(cd "$(dirname "$0")" && pwd -P)/$(basename "$0")
while [ -h "$SELF_PATH" ]; do
  # 1) cd to directory of the symlink
  # 2) cd to the directory of where the symlink points
  # 3) get the pwd
  # 4) append the basename
  DIR=$(dirname "$SELF_PATH")
  SYM=$(readlink "$SELF_PATH")
  SELF_PATH=$(cd "$DIR" && cd "$(dirname "$SYM")" && pwd)/$(basename "$SYM")
done
exec "$(dirname $SELF_PATH)/ruby" "$SELF_PATH" "$@"
BASH

Dir.glob("bin/*") do |file|
  contents = File.read(file)
  unless contents.start_with?(PRELUDE)
    puts "#{file} modified"
    contents = contents.sub(/\A#!.+ruby$/, '#!ruby')
    File.write file, "#{PRELUDE}\n#{contents}"
  end
end