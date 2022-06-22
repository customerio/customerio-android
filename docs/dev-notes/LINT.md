# Lint 

This project is setup to use [ktlint](https://ktlint.github.io/) to format and lint our Kotlin code. [Learn more about linting if you're not familiar](https://stackoverflow.com/questions/8503559/what-is-linting). 

We have setup the project so that if there are any lint errors, we do not merge a pull request. This means that it's important for you to check to see if there are any lint errors when you commit and push your code. Luckily, git hooks are setup to automatically run ktlint for you! This makes it easier to remember to run ktlint to find errors. 

### How to manually run ktlinter

Git hooks are setup to automatically run ktlint for you when you run git commands such as committing and pushing. But if you ever need to run ktlint manually, run the following command: `make lint`. 

### Lint errors 

When you run ktlint, you may see some output. This output is showing you lint errors that need to be addressed by yourself before a pull request gets merged. Here are some examples:
```
customerio-android/sdk/src/main/java/io/customer/sdk/data/model/EventType.kt:7:5: Enum entry name should be uppercase underscore-separated names like "ENUM_ENTRY" or upper camel-case like "EnumEntry" (cannot be auto-corrected) (enum-entry-name-case)

customerio-android/common-test/src/main/java/io/customer/common_test/BaseTest.kt:1:1: Package name must not contain underscore (cannot be auto-corrected) (package-name)

customerio-android/sdk/src/main/java/io/customer/sdk/data/request/Metric.kt:3:1: Imports must be ordered in lexicographic order without any empty lines in-between (import-ordering)
```

Let's say that you see this lint error:

```
customerio-android/sdk/src/main/java/io/customer/sdk/data/model/Fruit.kt:7:5: Enum entry name should be uppercase underscore-separated names like "ENUM_ENTRY" or upper camel-case like "EnumEntry" (cannot be auto-corrected) (enum-entry-name-case)
```

This error message is telling you that on line 7 of the file `sdk/src/main/java/io/customer/sdk/data/model/Fruit.kt`, there is an enum class where the cases are *not* named in the format: `ENUM_ENTRY` or `EnumEntry`. Let's say you open the file `EventType` and see this enum class:

```kotlin
enum class Fruit {
    apple, banana;
}
```

Now you realize why the lint error exists! `apple` and `banana` are not following the recommended format. If you edit `apple` to `APPLE` or `Apple` and `banana` to `Banana` or `BANANA` then that would make the lint error no longer show up. 

#### Fixing lint errors 

Let's use the `Fruit` enum example from the *Lint errors* section. 

There are various ways to fix lint errors. The methods are listed below from highest to lowest recommendation on when to use them. 
1. **(Preferred method) Edit your source code to fix the problem.**

When you read the ktlint errors, it should describe to you what the problem is so you know how to fix it yourself in the code. From reading the ktlint error message in our example (`Enum entry name should be uppercase...`), we can go into the Fruit enum class and modify the code to make the lint error go away. 

If we edit the enum class to:

```kotlin
enum class Fruit {
    Apple, Banana;
}
```

This should fix the lint error. Run ktlint manually again and you should no longer see the error. 

This method is the preferred method because ktlint rules are meant to prevent bugs, make code more maintainable, and help follow suggestions from Android and Kotlin community. If this rules are strongly recommended, it's usually a good idea to follow those rules in your code. 

2. **Disable the lint rule for that specific code**

Sometimes your code breaks a lint rule for a specific use case. Is there a reason that the `Fruit` enum's cases are lowercase? If so, it might be a good solution to disable the ktlint error only for the `Fruit` enum. 

There are [many ways to disable a lint rule for specific code](https://github.com/pinterest/ktlint#how-do-i-suppress-an-errors-for-a-lineblockfile=). 

```kotlin
// This method will disable only 1 line of code. 
enum class Fruit {    
    apple, banana; // ktlint-disable enum-entry-name-case (enum cases used as JSON values)
}
// Note: it's good documentation to give a reason why a ktlint rule is being disabled. 
// add a reason why as the example above: `(enum cases used as JSON values)`


// This method will disable a block of code in between "disable" and "enable"
// Handy to use when there are multiple lines that break the ktlint rule. 
enum class Fruit {    
    /* ktlint-disable enum-entry-name-case */
    // enum cases used as JSON values
    apple, 
    banana; 
    /* ktlint-enable enum-entry-name-case */
}
// Note: it's good documentation to give a reason why a ktlint rule is being disabled. 
// add a reason why as the example above: `// enum cases used as JSON values` 
```

Disabling a lint rule requires: (1) The line of code that break the kotlin rule and (2) the name of the kotlin rule that is broken. 

When we look at our ktlint error, we can get both of these pieces of information:

```
.../model/Fruit.kt:7:5: Enum entry name... (cannot be auto-corrected) (enum-entry-name-case)
                   ^ line of code                                      ^^^^^^^^^^^^^^^^^^^^ name of error
```

This means that if we want to disable the rule for this specific code, putting `// ktlint-disable enum-entry-name-case` on line 7 of the file will perform the disable. Now if you manually run ktlint again, you should not see the error anymore. 

3. **(Least preferred method) Disable a lint rule for all files in the source code project**

If there is a ktlint rule that your team has determined is not valuable to your code base and should always be ignored, you can disable a rule for all of the source code of your project. 

With the `Fruit` enum example, let's say that your team has made the decision that you want to make *all* enum classes in the entire project be all lowercase characters. To do this disabling, check out the file `.editorconfig`. You will find documentation in there to disable a particular rule. 