# R8 rules for :context-app's release build.
#
# :core ships consumer rules for its serializers and the AIDL stub; Room,
# WorkManager, Health Connect and Play services location bundle their own.
# Manifest components (service, receivers, workers via WorkManager's rules) are
# kept automatically. Only project-specific rules live here.

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
