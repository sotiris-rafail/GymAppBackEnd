package com.foundry.gym.mail;

/** The wording of the emails, in one place. Plain text on purpose: it reads the same in every mail program. */
final class EmailTexts {

    private EmailTexts() {
    }

    static String greeting(String name) {
        return "Hi " + name + ",\n\n";
    }

    /** Says why the email came and how to stop it. */
    static String footer(MailSettings.Snapshot props, String preferenceName) {
        String where = props.appUrl().isBlank() ? "the app" : props.appUrl();
        return "\n\n--\nYou get this email because you switched on \"" + preferenceName + "\" in your Preferences at "
            + props.gymName() + ". To stop it, open " + where + " and turn it off under Preferences.";
    }

    static String marketing(MailSettings.Snapshot props, String name, String text, String link) {
        return greeting(name) + (text == null ? "" : text.strip()) + (link == null || link.isBlank() ? "" : "\n\n" + link)
            + footer(props, "Marketing emails");
    }
}
