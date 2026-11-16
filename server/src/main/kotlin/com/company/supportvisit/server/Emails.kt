package com.company.supportvisit.server

/** Texts of the emails the server itself writes (the visit report text comes from the app). */
object Emails {

    private fun he(lang: String?) = lang?.lowercase()?.startsWith("he") == true || lang?.lowercase()?.startsWith("iw") == true

    fun loginCode(appName: String, code: String, lang: String?): Pair<String, String> =
        if (he(lang)) {
            "קוד הכניסה שלך: $code" to
                "קוד הכניסה שלך ל-$appName הוא:\n\n$code\n\nהקוד תקף ל-10 דקות. אם לא ביקשת אותו, אפשר להתעלם מההודעה."
        } else {
            "Your sign-in code: $code" to
                "Your $appName sign-in code is:\n\n$code\n\nIt expires in 10 minutes. If you didn't ask for it, you can ignore this email."
        }

    fun invitation(appName: String, company: String, inviter: String, email: String, lang: String?): Pair<String, String> =
        if (he(lang)) {
            "הוזמנת להצטרף ל-$company" to
                "$inviter הזמין/ה אותך להצטרף ל-$company ב-$appName.\n\n" +
                "התקינו את האפליקציה והתחברו עם כתובת האימייל הזו: $email"
        } else {
            "You're invited to join $company" to
                "$inviter invited you to join $company on $appName.\n\n" +
                "Install the app and sign in with this email address: $email"
        }
}
