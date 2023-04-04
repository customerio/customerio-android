# Kotlin Binary Validator

As SDK code is evolving over time, we need to be mindful of changes in public API. To achieve this

we use the [Kotlin binary compatibility validator plugin](https://github.com/Kotlin/binary-compatibility-validator) to keep track of all the changes in our public API.

## How does it work?

For each module It generates `.api` files that describe the public API available, and if you make changes to public API, you have to explicitly update the content of the `.api` files (otherwise, checks will fail, alerting you of the accidental API change).

## Setup

Since it is a simple Gradle plugin, we just need to add this code to our top level `build.gradle` file:

```groovy
apply plugin: 'binary-compatibility-validator'

buildscript {
    dependencies {
        classpath Dependencies.kotlinBinaryValidator
    }
}
```

## Configuration

We can configure what we want to include in the `apiValidation` block.

```groovy
apiValidation {  
  ignoredPackages += [  
            'io/customer/base/extenstions',  
            'io/customer/sdk/api',  
            'io/customer/sdk/data/store',  
            'io/customer/sdk/queue',  
            'io/customer/sdk/extensions',  
            'io/customer/sdk/util',  
    ]  
  
  ignoredProjects += [  
			'common-test',  
            'app',  
    ]  
  
  ignoredClasses.add("io.customer.messagingpush.BuildConfig")  
  ignoredClasses.add("io.customer.messaginginapp.BuildConfig")  
  ignoredClasses.add("io.customer.sdk.BuildConfig")  
}
```

 - **ignoredPackages** allows you to exclude some packages from validation.
 - **ignoredProjects** allows you to exclude non-published modules
 - **ignoredClasses** allows you to exclude classes from public api dump

> More configuration options can be found [here](https://github.com/Kotlin/binary-compatibility-validator#optional-parameters)

## Usage

- To generate/re-generate the `.api` files, you'll have to run the `apiDump`
- Use `apiCheck` To verify that your current source code still has the same API as your committed `.api` files.

## Integrations

These `checks` are currently integrated with both 
- `lefthook` - Using a pre-commit from lefthook, it will run the `apiCheck` task (and automatically run `apiDump` for you if it fails) when you try to create a new commit in the repository.
- `github action` - to makes sure that the `api` files are up-to-date before we merge changes. 
