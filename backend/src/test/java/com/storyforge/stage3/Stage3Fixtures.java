package com.storyforge.stage3;

import java.util.List;

/** Shared shot plan for tests in other packages: one shot per line, Pip in every 5th. */
public final class Stage3Fixtures {

    private Stage3Fixtures() {
    }

    public static String plan() {
        return Stage3ScenariosTest.plan(i -> i % 5 == 0 ? List.of(Stage3ScenariosTest.PIP) : List.of());
    }
}
