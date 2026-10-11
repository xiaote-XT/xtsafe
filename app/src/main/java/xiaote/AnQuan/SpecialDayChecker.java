package xiaote.AnQuan;

import android.app.Activity;
import android.app.AlertDialog;

import java.util.Calendar;

public class SpecialDayChecker {

    private static class SpecialDay {
        int month, day;
        String titleZh, descZh, titleZhTW, descZhTW, titleEn, descEn, titleJa, descJa;
        SpecialDay(int month, int day,
                   String titleZh, String descZh, String titleZhTW, String descZhTW,
                   String titleEn, String descEn, String titleJa, String descJa) {
            this.month = month; this.day = day;
            this.titleZh = titleZh; this.descZh = descZh;
            this.titleZhTW = titleZhTW; this.descZhTW = descZhTW;
            this.titleEn = titleEn; this.descEn = descEn;
            this.titleJa = titleJa; this.descJa = descJa;
        }
    }

    private static final SpecialDay[] SPECIAL_DAYS = {
        new SpecialDay(1, 1,
            "元旦", "新的一年，新的开始！",
            "元旦", "新的一年，新的開始！",
            "元旦", "新的一年，新的开始！",
            "元旦", "新的一年，新的开始！"),

        new SpecialDay(7, 7,
            "七七事变纪念日", "1937年7月7日，卢沟桥事变爆发，中华民族全面抗战开始。铭记历史，吾辈自强！打倒日本帝国主义！",
            "七七事變紀念日", "1937年7月7日，盧溝橋事變爆發，中華民族全面抗戰開始。銘記歷史，吾輩自強！打倒日本帝國主義！",
            "July 7 Incident", "On July 7, 1937, the Marco Polo Bridge Incident marked the beginning of full-scale Chinese resistance. Remember history, strengthen ourselves! Down with Japanese imperialism!",
            "七七事変記念日", "1937年7月7日、盧溝橋事件が勃発、中華民族の全面抗戦が始まりました。歴史を銘記し、自らを強くしましょう！日本帝国主義を打倒せよ！"),

        new SpecialDay(8, 15,
            "日本投降日", "1945年8月15日，日本宣布无条件投降。铭记历史，珍爱和平，吾辈当自强！打倒日本帝国主义！",
            "日本投降日", "1945年8月15日，日本宣布無條件投降。銘記歷史，珍愛和平，吾輩當自強！打倒日本帝國主義！",
            "Japan's Surrender Day", "On August 15, 1945, Japan announced unconditional surrender. Remember history, cherish peace, strengthen ourselves! Down with Japanese imperialism!",
            "日本の降伏日", "1945年8月15日、日本は無条件降伏を宣言しました。歴史を銘記し、平和を大切にし、自らを強くしましょう！日本帝国主義を打倒せよ！"),

        new SpecialDay(9, 3,
            "抗日战争胜利纪念日", "1945年9月3日，中国人民抗日战争胜利纪念日。铭记历史，缅怀先烈，珍爱和平，开创未来！打倒日本帝国主义！",
            "抗日戰爭勝利紀念日", "1945年9月3日，中國人民抗日戰爭勝利紀念日。銘記歷史，緬懷先烈，珍愛和平，開創未來！打倒日本帝國主義！",
            "Victory Day", "September 3, 1945 marks the victory of the Chinese People's War of Resistance. Remember history, honor the martyrs, cherish peace, create the future! Down with Japanese imperialism!",
            "抗日戦争勝利記念日", "1945年9月3日、中国人民抗日戦争勝利記念日。歴史を銘記し、先烈を偲び、平和を大切にし、未来を切り開きましょう！日本帝国主義を打倒せよ！"),

        new SpecialDay(9, 18,
            "九一八事变纪念日", "1931年9月18日，九一八事变爆发。勿忘国耻，振兴中华！打倒日本帝国主义！",
            "九一八事變紀念日", "1931年9月18日，九一八事變爆發。勿忘國恥，振興中華！打倒日本帝國主義！",
            "September 18 Incident", "On September 18, 1931, the September 18 Incident occurred. Never forget the national humiliation, revitalize China! Down with Japanese imperialism!",
            "九一八事変記念日", "1931年9月18日、九一八事変が勃発。国辱を忘れず、中華を振興しましょう！日本帝国主義を打倒せよ！"),

        new SpecialDay(10, 1,
            "国庆节", "祝祖国繁荣昌盛，国泰民安！",
            "國慶節", "祝祖國繁榮昌盛，國泰民安！",
            "国庆节", "祝祖国繁荣昌盛，国泰民安！",
            "国庆节", "祝祖国繁荣昌盛，国泰民安！"),

        new SpecialDay(12, 13,
            "南京大屠杀死难者国家公祭日", "1937年12月13日，南京沦陷。铭记历史，勿忘国耻，振兴中华！打倒日本帝国主义！",
            "南京大屠殺死難者國家公祭日", "1937年12月13日，南京淪陷。銘記歷史，勿忘國恥，振興中華！打倒日本帝國主義！",
            "National Memorial Day", "On December 13, 1937, Nanjing fell. Remember history, never forget the national humiliation, revitalize China! Down with Japanese imperialism!",
            "南京大虐殺犠牲者国家追悼日", "1937年12月13日、南京が陥落。歴史を銘記し、国辱を忘れず、中華を振興しましょう！日本帝国主義を打倒せよ！")
    };

    public static void check(Activity activity) {
        try {
            Calendar cal = Calendar.getInstance();
            int month = cal.get(Calendar.MONTH) + 1;
            int day = cal.get(Calendar.DAY_OF_MONTH);
            for (SpecialDay sd : SPECIAL_DAYS) {
                if (sd.month == month && sd.day == day) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(sd.titleZh).append("\n").append(sd.descZh).append("\n\n");
                    sb.append(sd.titleZhTW).append("\n").append(sd.descZhTW).append("\n\n");
                    sb.append(sd.titleEn).append("\n").append(sd.descEn).append("\n\n");
                    sb.append(sd.titleJa).append("\n").append(sd.descJa);
                    new AlertDialog.Builder(activity)
                            .setTitle(sd.titleZh)
                            .setMessage(sb.toString())
                            .setPositiveButton("确定", null).show();
                    break;
                }
            }
        } catch (Exception ignored) {}
    }
}