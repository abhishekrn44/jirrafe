# Homebrew formula. Publish it in a tap (`homebrew-jirrafe`); the release workflow's fat jar is the
# artifact. Replace the sha256 on each release.
class Jirrafe < Formula
  desc "Code graph and knowledge graph of JVM projects, including internal jars, served over MCP"
  homepage "https://github.com/abhishekrn44/jirrafe"
  url "https://github.com/abhishekrn44/jirrafe/releases/download/v0.1.0/jirrafe-0.1.0-all.jar"
  sha256 "REPLACE_WITH_SHA256_OF_THE_JAR"
  license "Apache-2.0"

  depends_on "openjdk@17"

  def install
    libexec.install "jirrafe-#{version}-all.jar"
    (bin/"jirrafe").write <<~SH
      #!/bin/bash
      exec "#{Formula["openjdk@17"].opt_bin}/java" -jar "#{libexec}/jirrafe-#{version}-all.jar" "$@"
    SH
  end

  test do
    assert_match "Commands:", shell_output("#{bin}/jirrafe --help")
  end
end
