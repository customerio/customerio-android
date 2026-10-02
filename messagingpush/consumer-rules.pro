# FirebaseTokenSource calls FirebaseMessaging.register() (firebase-messaging 25.1.0+) through
# reflection, which R8 can't always trace. Keep it from being removed or renamed. The rule matches
# nothing on older Firebase.
-keepclassmembers class com.google.firebase.messaging.FirebaseMessaging {
    public *** register();
}
