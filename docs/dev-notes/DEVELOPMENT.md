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

* Setup git hooks to lint your code for you:

```
$ ./hooks/autohook.sh install
[Autohook] Scripts installed into .git/hooks
```

* Install `ktlint` Kotlin linting CLI tool. The easiest way is `brew install ktlint` but if you are not on a Mac, [find another way to install](https://ktlint.github.io/#getting-started) on your machine. 

### Development workflow 

Let's say that you make changes to this project and you want your changes to become part of the project. This is the workflow that we follow to make that happen:

1. Make a new git branch where your changes will occur. 
2. Perform your changes on this branch. This part is very important. It's important that your git branches are focused with 1 goal in mind. 

Let's say that you decide to do all of this work under 1 git branch:
* Add a new feature to the app. 
* Fix a few bugs. 

....this is an anti-pattern in this repository. Instead...

* Make 1 git branch for your new feature that you add to the app. 
* Make 1 git branch for each bug that you fix. 

*Note: If you fix a bug or add a feature that it requires you edit the documentation, it's suggested to make all documentation changes in the git branch for that bug or feature. Not recommended to make separate branches just for documentation changes.*

Why do we do it this way? 
* It makes our git commit history cleaner. When you think to yourself, "Oh, what was that change I made 3 months ago?" you can more easily find those commits because they are focused. 
* It helps the team deploy the code more easily to customers. 
* It makes code reviews much easier as pull requests are now more focused. 
* It is easier to find what commits break code. When we make small pull requests, we are able to isolate those changes from each other and more easily find what changes cause what issues. 

3. Make a pull request. 

When you make a pull request, a team member will merge it for you after...
* All automated tests pass. If they don't it's your responsibility to fix them. 
* A team member will review your code. If they have suggestions on how to fix it, you can discuss the suggestions as a team and/or make changes to your code from those suggestions. 
* The title of the pull request is named following the [conventional commit message](https://gist.github.com/levibostian/71afa00ddc69688afebb215faab48fd7) specification. 

### Deployment 

This project is setup to automatically deploy when a commit is made on any of the branches: `main`, `beta`, and `alpha`. 

To setup the CI server to make the deployments, follow these steps:
1. Create a GitHub token on your personal GitHub account. As long as your account has push access to the SDK source code repository on GitHub. The scopes need to (1) public repos to write to repos. 

Create GitHub Actions secrets: 
* `REPO_PUSH_TOKEN` - set the value to the GitHub token created in this step. This is used to push commits made on the CI server back to the source code repo after deployment. 
* `GRADLE_PUBLISH_USERNAME` - set the value to Sonatype Jira username to push to Sonatype repository. 
* `GRADLE_PUBLISH_PASSWORD` - set the value to Sonatype Jira password. 
* `GRADLE_SIGNING_KEYID` - The signing plugin says "The public key ID (The last 8 symbols of the keyId. You can use gpg -K to get it)." The important part there is the last 8 characters of the keyid. Use command `gpg --list-secret-keys --keyid-format long` and the last 8 characters of the `[S]` subkey's keyId is the value of this environment variable. So if the key is woijeyrinvnno22o2n, you will set nno22o2n as the value on your CI server.
* `GRADLE_SIGNING_PRIVATE_KEY` - This is the secret subkey in armor format. On your machine, run `gpg --export-secret-subkeys --armor -o /tmp/subkeys.key XXXXXXXXXXXXXXXX` (where XXX is the master key ID). You will enter the passphrase for your master key and then your subkeys will be generated to the output file. Now, you can run `cat /tmp/subkeys.key` to get the contents of this environment variable. Note: If you don't want to sign with subkeys but instead master key, you would use --export-secret-keys instead of --export-secret-subkeys for the above command but everything else stays the same with the command.
* `GRADLE_SIGNING_PASSPHRASE` - this is your master key GPG passphrase.

2. Follow the development workflow as described in this document. You will be making pull requests and once those are merged, the CI server will automatically execute and deploy for you. 

#### GPG signing

This project is setup with GPG signing to sign the Maven artifacts before they are uploaded to Sonatype's servers to sync to Maven Central. GPG keys have been generated already but if you need to generate them again, [see this guide](https://gist.github.com/levibostian/ed2edcaa1ce1722d70683ce83fc429e2#sign) to do so. 

