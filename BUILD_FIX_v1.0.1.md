# v1.0.1 build fix

Fixes the user-reported `:app:compileDebugKotlin` error: `public function exposes its internal parameter type argument GmailPreview`.

The sole Kotlin change is to make the GmailPreview data class public. No database schema, package ID, or Gmail scan behavior was changed.

Build validation has not been performed here; open this project in Android Studio and run Build > Rebuild Project. Report any remaining compiler errors.
