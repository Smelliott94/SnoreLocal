Run `python3 tools/setup.py` from the project root to populate `yamnet.tflite`
and `yamnet.sha256`. Both are packaged into the APK. Missing assets fail the build.
The first HTTPS download establishes the local digest; it is not a vendor signature.
Commit both resulting files to preserve that exact model across builds.
