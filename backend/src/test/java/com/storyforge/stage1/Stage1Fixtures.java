package com.storyforge.stage1;

/** Shared fake-Claude answers for tests in other packages. Script lines get distinct texts ("w1 ...", "w2 ..."). */
public final class Stage1Fixtures {

    private Stage1Fixtures() {
    }

    public static String story(String newId, String newName, String voice) {
        return Stage1ScenariosTest.story(newId, newName, voice);
    }

    public static String qaPass(String... names) {
        return Stage1ScenariosTest.qaPass(names);
    }

    /** {@code lines} lines of 13 words, each starting with a unique word w1, w2, ...; every 5th line is the character. */
    public static String script(String characterId, int lines) {
        String base = Stage1ScenariosTest.script(characterId, lines);
        StringBuilder out = new StringBuilder();
        int i = 0;
        int from = 0;
        String marker = "\"text\":\"one ";
        int at;
        while ((at = base.indexOf(marker, from)) >= 0) {
            i++;
            out.append(base, from, at).append("\"text\":\"w").append(i).append(' ');
            from = at + marker.length();
        }
        return out.append(base.substring(from)).toString();
    }
}
