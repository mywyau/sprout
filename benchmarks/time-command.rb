# frozen_string_literal: true

label = ARGV.shift
if ARGV.first == "--chdir"
  ARGV.shift
  directory = ARGV.shift
end
abort "usage: time-command.rb LABEL [--chdir DIRECTORY] COMMAND [ARGUMENT ...]" unless label && !ARGV.empty?

started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
options = { out: File::NULL }
options[:chdir] = directory if directory
success = system(*ARGV, **options)
elapsed = Process.clock_gettime(Process::CLOCK_MONOTONIC) - started

abort "benchmark command failed: #{ARGV.join(' ')}" unless success

puts format("%s\t%.1f", label, elapsed * 1000.0)
