# Contributing

Thanks for looking. Bug reports are the most useful thing you can send, and a pull request that
fixes one is better still.

## Reporting a bug

Open an issue. The things that actually help:

- What you did, what happened, and what you expected instead.
- Your Android version and phone, and which build you installed (Play flavour or F-Droid).
- The app version, from Settings at the bottom of the screen.

If the app crashed, Settings has a crash log viewer. The logs hold no readings, no food and no
addresses, so they are safe to paste into an issue. Screenshots are welcome; blank them where a
weight appears if you would rather not share it.

Please do not put a security problem in an issue. See [SECURITY.md](SECURITY.md).

## Asking for a feature

Say what you are trying to do rather than what you want built. Half the good ideas in this app
arrived as somebody describing a problem, and the shape they suggested was not the shape it ended
up as.

Some things will not be built, and the "Never" section at the bottom of
[ROADMAP.md](../ROADMAP.md) says which: ads, subscriptions, accounts, a proprietary cloud, an
automated coach, a social feed, and moving anything that already ships behind anything at all.

## Sending a pull request

You need Android Studio, or the command line SDK with JDK 17 or newer.

```
./gradlew :core:testDebugUnitTest        # the shared maths
./gradlew :app:testPlayDebugUnitTest     # the phone app
./gradlew :app:testFossDebugUnitTest     # and the F-Droid flavour
./gradlew :wear:testDebugUnitTest        # the watch
./gradlew :app:lintPlayDebug
./gradlew :app:assemblePlayDebug :app:assembleFossDebug
```

Run all of those before you open the pull request. There is no CI here on purpose: everything is
built and checked on a machine somebody owns.

What gets a change merged quickly:

- A test that fails before the change and passes after it. If you cannot write one, say so and
  say why.
- Everything a person reads kept in `res/values/strings.xml` rather than in the code, so it can
  be translated.
- Weight held as whole grams. Kilograms, pounds and stones are presentation, and anything that
  rounds a stored reading will be sent back.
- A small diff. Unrelated tidying in the same commit makes a change harder to judge, and it is
  the reason a lot of pull requests sit unread.

The maths lives in `core`, which is plain Kotlin with no Android imports and is shared with the
watch. If something can go there, it should: it is the part that can be tested properly.

## Translations

Everything a person reads is already in `app/src/main/res/values/strings.xml`. A translation is a
new `values-xx/strings.xml` beside it and nothing else. Plurals matter here; several strings use
them, and a language with different rules needs its own.

## What the code looks like

Read a file next to the one you are changing and follow it. Comments explain why a thing is the
way it is, not what the line does, and the ones worth writing are the ones that stop somebody
undoing a fix later.
