import re

with open('app/src/test/java/io/github/daddymean/agentickeyboard/util/KeyboardPassportTransferBenchmark.kt', 'r') as f:
    content = f.read()

content = content.replace(
    "@RunWith(RobolectricTestRunner::class)",
    "@RunWith(RobolectricTestRunner::class)\n@org.robolectric.annotation.Config(sdk = [35])"
)

with open('app/src/test/java/io/github/daddymean/agentickeyboard/util/KeyboardPassportTransferBenchmark.kt', 'w') as f:
    f.write(content)
