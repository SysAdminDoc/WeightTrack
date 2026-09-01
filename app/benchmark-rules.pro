# Kept on top of the release rules for the benchmark build type only.
#
# The fixture is seeded by a broadcast the benchmark sends from outside the process. Nothing
# inside the app calls it, so the shrinker is right to think it is dead and has to be told
# otherwise. This file is not part of any build anybody installs.
-keep class com.weighttrack.benchmark.FixtureSeeder { *; }
