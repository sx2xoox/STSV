package dev.stsv.economy.secret.newcomer;

import java.util.ArrayList;
import java.util.List;

/**
 * The night after he left: at the hour he used to come, a letter in the mailbox of everyone who ever spoke to him, each
 * different by what they were to him ({@link Memories#kind}). The one who signed gets a sheet with nothing on it. The
 * postscript is the same for all. These are the scripted letters; the model may write the body instead (and when it
 * cannot, these are sent). Pure.
 */
final class Letters {

    private Letters() {}

    static final String PS = "…이제 질문은 없어요.";

    /** Whether this letter is the empty sheet. */
    static boolean blank(Memories.Kind kind) {
        return kind == Memories.Kind.WITNESS;
    }

    /** The scripted body for someone ({player}: their name as he called them; detail: what their shard brought, or ""). */
    static List<String> body(Memories.Kind kind, String player, String detail) {
        List<String> l = new ArrayList<>();
        switch (kind) {
            case WITNESS -> {
                // nothing: an empty sheet
            }
            case NAMER -> {
                l.add(player + "님이 제 이름을 불러 주셨죠.");
                l.add("그 소리, 정말 오랜만에 들었어요.");
                l.add("제 이름이 다시 돌에 있는 걸 보고 갔어요.");
                l.add("고마웠어요.");
            }
            case GIVER -> {
                l.add(detail == null || detail.isBlank() ? player + "님이 넣어 준 조각, 기억해요."
                        : player + "님이 넣어 준 조각에 " + detail + " 있었어요.");
                l.add("어디서 찾으셨는지는 끝내 못 물어봤네요.");
                l.add("덕분에 그날이 다시 그날이 됐어요.");
                l.add("고마웠어요.");
            }
            case RUDE -> {
                l.add("그날은 제가 먼저 가서 죄송했어요.");
                l.add("놀라서 그랬어요.");
                l.add("그래도 " + player + "님 말, 다 들었어요.");
            }
            case TEASER -> {
                l.add("봇이라고 하셨죠.");
                l.add("맞았을지도 몰라요.");
                l.add("그래도 말 걸어 주셔서 좋았어요.");
            }
            case ANSWERER -> {
                l.add(player + "님이 답해 주신 것들, 다 적어 뒀어요.");
                l.add("쓸 일은 없었지만요.");
                l.add("물어볼 때마다 답이 와서 좋았어요.");
                l.add("고마웠어요.");
            }
            default -> {
                l.add(player + "님, 저한테 말 걸어 주셨죠.");
                l.add("대답을 잘 못 해서 미안했어요.");
                l.add("그래도 기억하고 있어요.");
            }
        }
        return l;
    }

    /** The whole letter as it is kept and shown: the body, an empty line, the postscript. */
    static List<String> whole(List<String> body) {
        List<String> out = new ArrayList<>(body);
        if (!out.isEmpty()) out.add("");
        out.add(PS);
        return out;
    }
}
