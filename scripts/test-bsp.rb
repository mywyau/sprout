#!/usr/bin/env ruby

require "json"
require "open3"
require "pathname"
require "timeout"

abort "usage: #{$PROGRAM_NAME} SPROUT PROJECT_DIRECTORY" unless ARGV.length == 2

sprout = Pathname.new(ARGV[0]).expand_path
project = Pathname.new(ARGV[1]).expand_path
stdin, stdout, stderr, wait = Open3.popen3(sprout.to_s, "bsp", chdir: project.to_s)
stderr_thread = Thread.new { STDERR.write(stderr.read) }

def send_message(stream, message)
  body = JSON.generate(message)
  stream.write("Content-Length: #{body.bytesize}\r\n\r\n#{body}")
  stream.flush
end

def read_message(stream)
  length = nil
  while (line = stream.gets("\r\n"))
    break if line == "\r\n"
    length = line.split(":", 2).last.to_i if line.downcase.start_with?("content-length:")
  end
  raise "BSP response did not include Content-Length" unless length

  JSON.parse(stream.read(length))
end

def read_response(stream, id)
  loop do
    message = read_message(stream)
    return message if message["id"] == id
  end
end

root_uri = project.to_s.end_with?(File::SEPARATOR) ? project.to_s : "#{project}#{File::SEPARATOR}"
root_uri = Pathname.new(root_uri).to_s
root_uri = "file://#{root_uri}"

send_message(stdin, {
  jsonrpc: "2.0",
  id: 1,
  method: "build/initialize",
  params: {
    displayName: "Sprout release test",
    version: "1",
    bspVersion: "2.1.0",
    rootUri: root_uri,
    capabilities: { languageIds: ["scala"] }
  }
})
initialize = read_response(stdout, 1)
raise "BSP initialization failed: #{initialize}" unless initialize.dig("result", "displayName") == "Sprout"

send_message(stdin, { jsonrpc: "2.0", method: "build/initialized" })
send_message(stdin, { jsonrpc: "2.0", id: 2, method: "workspace/buildTargets", params: {} })
targets = read_response(stdout, 2).dig("result", "targets")
raise "BSP did not return main and test targets" unless targets&.length == 2

main_target = targets.find { |target| target.fetch("displayName").end_with?("(main)") }
raise "BSP did not return a main target" unless main_target

send_message(stdin, {
  jsonrpc: "2.0",
  id: 3,
  method: "buildTarget/scalacOptions",
  params: { targets: [main_target.fetch("id")] }
})
options = read_response(stdout, 3).dig("result", "items", 0)
unless options&.fetch("classpath", [])&.any? { |entry| entry.include?("scala3-library_3") }
  raise "BSP compiler classpath did not contain the Scala library"
end

send_message(stdin, {
  jsonrpc: "2.0",
  id: 4,
  method: "buildTarget/compile",
  params: { targets: [main_target.fetch("id")], originId: "release-test" }
})
compile = read_response(stdout, 4)
raise "BSP compilation failed: #{compile}" unless compile.dig("result", "statusCode") == 1

semanticdb = project.join(".sprout/classes/META-INF/semanticdb/src/main/scala/Main.scala.semanticdb")
raise "BSP compilation did not produce SemanticDB" unless semanticdb.file?

send_message(stdin, { jsonrpc: "2.0", id: 5, method: "build/shutdown", params: {} })
read_response(stdout, 5)
send_message(stdin, { jsonrpc: "2.0", method: "build/exit" })
begin
  Timeout.timeout(5) { wait.value }
rescue Timeout::Error
  Process.kill("TERM", wait.pid) if wait.alive?
  raise "BSP server did not exit after build/exit"
ensure
  stdin.close unless stdin.closed?
end
stderr_thread.join

puts "BSP release smoke test passed"
