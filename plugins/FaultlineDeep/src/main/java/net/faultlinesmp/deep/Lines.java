package net.faultlinesmp.deep;

import java.util.Random;

/** What Halcyon's citizens say (speech bubbles over their heads). */
final class Lines {
    private Lines() { }

    static String pick(String[] l, Random r) { return l[r.nextInt(l.length)]; }

    static final String[] MEAL = {"Mm, kelp bread again.", "Pass the salt, would you?", "Best cod this side of the Trench.", "Breakfast first. Always.",
            "The Kelp & Kettle's stew is better.", "Another day under the glass."};
    static final String[] GUARD = {"Move along, citizen.", "Keep your hands where I can see them.", "All quiet on the ring.", "No trouble today, alright?",
            "The radar's watching. So am I.", "Halcyon Security. Nothing to see.", "I trained for twenty years for this patrol."};
    static final String[] EVENING = {"Lovely evening under the sea.", "Did you see the Glimmer last night?", "My back's killing me.", "The dome creaked again today.",
            "Heard the subs found something big out east.", "Ever wonder what the sky looks like?", "Another good day's work."};
    static final String[] CHILD = {"Tag! You're it!", "Can't catch me!", "I'm gonna be a sub pilot!", "Hehe!", "Wanna see my shell collection?", "Race you to the fountain!"};
    static final String[] DOCK = {"Fancy a Submersible? 128 emeralds.", "She handles like a dream.", "Patrols are out. Radar's clear.", "Mind the berths, they're slippery.",
            "Every sub's tested at the Trench. Mostly."};
    static final String[] WOOD = {"Timber!", "Grows slow down here, but it grows.", "Mind the axe.", "Another one for the pile."};
    static final String[] MINE = {"Coal, coal and more coal.", "Watch your head down here.", "Found a good seam today.", "Keep the lanterns lit."};
    static final String[] LAB = {"Fascinating...", "The specimen moved. Did it move?", "Note to self: never tap the glass.", "Sample twelve is growing again.",
            "The Titan's readings are off the charts.", "Don't touch anything green."};
    static final String[] AQUA = {"Feeding time!", "Don't tap the glass, please.", "That one bit me yesterday.", "Ninety-odd species. All accounted for.",
            "The anglers love the dark tank."};
    static final String[] MARKET = {"Fresh from the deep!", "Best prices in Halcyon!", "Come, come, have a look!", "Emeralds only, friend.", "Everything must go!"};
    static final String[] CURATOR = {"Welcome to the Aquarium!", "The Bestiary's on the lectern. Read it!", "Every creature of the Dark Ocean, under one roof.",
            "Please don't feed the Gulper Eel."};
    static final String[] COOK = {"Stew's on!", "Who ordered the cod?", "Mind the hot plates!", "Kelp soup, fresh as it gets."};
    static final String[] PANIC = {"AAAH!", "Help! Help!", "Run!", "Guards! GUARDS!", "Someone call security!"};
    static final String[] CHASE = {"Halt! In the name of Halcyon!", "Stop right there, criminal scum!", "You're coming with me!", "Got you now!"};
    static final String[] NITWIT = {"Hmm?", "Huh.", "I was told there'd be snacks.", "Zzz... wha?"};
}
