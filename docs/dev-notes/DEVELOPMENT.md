# Development Getting Started 

### Development getting started

Let's get the code compiling successfully on your machine. 

*Note: It's assumed that you have [Android Studio](https://developer.android.com/studio/) installed on your machine before continuing*

1. Clone the git repo to your computer.
2. Open Android Studio > Select "Import Project (Gradle...)" > then choose the root directory of the code (you will see `app/` and `sdk/` directories). 

![select import project in Android Studio menu](/misc/android_studio_import.jpeg)

3. Android Studio should walk you through installing the correct Android SDK for the code base. 

If all went well, you should be able to build the Example app within Android Studio. This is a good sign you're setup correctly.

![visual on how to select 'app' from build configuration dropdown](/misc/build_android_studio.jpeg)

### Development environment 

Now that you have gotten the app to successfully compile, it's time for you to finish setting up the rest of your development environment. 

* Setup git hooks using the tool [lefthook](https://github.com/evilmartians/lefthook):

```
$ brew install lefthook 
$ lefthook install 
SYNCING
SERVED HOOKS: pre-commit, prepare-commit-msg
```

# Lint

See [LINT](LINT.md) to learn more about linting in this project.

### Development workflow 

See file [GIT-WORKFLOW](GIT-WORKFLOW.md) to learn about the workflow that this project uses. 

### Deployment 

See file [GIT-WORKFLOW](GIT-WORKFLOW.md) to learn about how code is deployed in this project. 

#### Deployment setup 

To setup the CI server to make the deployments, follow these steps:
Create GitHub Actions secrets: 
* `GRADLE_PUBLISH_USERNAME` - set the value to Sonatype Jira username to push to Sonatype repository. 
* `GRADLE_PUBLISH_PASSWORD` - set the value to Sonatype Jira password. 
* `GRADLE_SIGNING_KEYID` - The signing plugin says "The public key ID (The last 8 symbols of the keyId. You can use gpg -K to get it)." The important part there is the last 8 characters of the keyid. Use command `gpg --list-secret-keys --keyid-format long` and the last 8 characters of the `[S]` subkey's keyId is the value of this environment variable. So if the key is woijeyrinvnno22o2n, you will set nno22o2n as the value on your CI server.
* `GRADLE_SIGNING_PRIVATE_KEY` - This is the secret subkey in armor format. On your machine, run `gpg --export-secret-subkeys --armor -o /tmp/subkeys.key XXXXXXXXXXXXXXXX` (where XXX is the master key ID). You will enter the passphrase for your master key and then your subkeys will be generated to the output file. Now, you can run `cat /tmp/subkeys.key` to get the contents of this environment variable. Note: If you don't want to sign with subkeys but instead master key, you would use --export-secret-keys instead of --export-secret-subkeys for the above command but everything else stays the same with the command.
* `GRADLE_SIGNING_PASSPHRASE` - this is your master key GPG passphrase.
* `SLACK_NOTIFY_RELEASES_WEBHOOK_URL` - create an incoming webhook URL to be able to send messages to a channel in your Slack workspace. [Learn more](https://github.com/openhousepvt/slack#slack_webhook_url-required). 

2. Follow the development workflow as described in this document. You will be making pull requests and once those are merged, the CI server will automatically execute and deploy for you. 

#### GPG signing

This project is setup with GPG signing to sign the Maven artifacts before they are uploaded to Sonatype's servers to sync to Maven Central. GPG keys have been generated already but if you need to generate them again, [see this guide](https://gist.github.com/levibostian/ed2edcaa1ce1722d70683ce83fc429e2#sign) to do so. 

# Troubleshooting

For known local setup issues and fixes, see [TROUBLESHOOTING.md](TROUBLESHOOTING.md).
