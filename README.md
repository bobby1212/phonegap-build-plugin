# Jenkins Phonegap Build Plugin

https://wiki.jenkins.io/display/JENKINS/Phonegap+Build+Plugin

This plugins will use Phonegap Build APIs to allow your Jenkins to build binaries
for Android, Windows, iOS, as allowed by the services from build.phonegap.com

You should provide the job configuration with your Phonegap Build's API token, and with the signing keys' IDs.

Your working copy will be compressed and sent to phonegap build.

New application slots will be created on demand if needed (and allowed by your Phonegap Build account), and then the system will monitor the status until the binaries are ready or an error is reported.

