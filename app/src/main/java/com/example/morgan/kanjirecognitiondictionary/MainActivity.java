package com.example.morgan.kanjirecognitiondictionary;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;

import android.view.View.OnClickListener;

import com.example.morgan.kanjirecognitiondictionary.parsers.JSONReader;
import com.example.morgan.kanjirecognitiondictionary.providers.HttpDictonaryProvider;
import com.example.morgan.kanjirecognitiondictionary.util.InputStroke;
import com.example.morgan.kanjirecognitiondictionary.util.KanjiInfo;
import com.example.morgan.kanjirecognitiondictionary.util.KanjiList;
import com.example.morgan.kanjirecognitiondictionary.util.KanjiMatch;
import com.example.morgan.kanjirecognitiondictionary.util.MultiAssetInputStream;
import com.example.morgan.kanjirecognitiondictionary.util.Pair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedList;

import static com.example.morgan.kanjirecognitiondictionary.ResultsActivity.*;

public class MainActivity extends AppCompatActivity {
    private KanjiDrawing kanjiDrawing;

    /**
     * Selected kanji in result (if any).
     */
    public final static String EXTRA_KANJI = "kanji";

    /**
     * Current result stage in intent.
     */
    public final static String EXTRA_STAGE = "stage";

    private final static int STAGE_EXACT = 1, STAGE_FUZZY = 2,
            STAGE_MOREFUZZY = 3, STAGE_PLUSMINUS1 = 4,
            STAGE_MOREPLUSMINUS1 = 5, STAGE_EVENMOREPLUSMINUS1 = 6;

    private static KanjiList list;
    private static boolean listLoading;
    private static Object listSynch = new Object();

    private static Handler mHander;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.pickkanji);

        new LoadThread();

        LinearLayout linearLayout = (LinearLayout) findViewById(R.id.drawcontainer);
        kanjiDrawing = new KanjiDrawing(this);
        linearLayout.addView(kanjiDrawing);

        TextView strokesText = (TextView) findViewById(R.id.strokes);
        final int normalRgb = strokesText.getTextColors().getDefaultColor();

        kanjiDrawing.setListener(new KanjiDrawing.Listener() {
            @Override
            public void strokes(KanjiDrawing.DrawnStroke[] strokes) {
                findViewById(R.id.undo).setEnabled(strokes.length > 0);
                findViewById(R.id.clear).setEnabled(strokes.length > 0);

                boolean gotList;
                synchronized(listSynch) {
                    gotList = list != null;
                }
                findViewById(R.id.done).setEnabled(strokes.length > 0 && gotList);

                TextView strokesText = (TextView)findViewById(R.id.strokes);
                strokesText.setText(strokes.length + "");
                if(strokes.length == KanjiDrawing.MAX_STROKES) {
                    strokesText.setTextColor(Color.RED);
                }
                else {
                    strokesText.setTextColor(normalRgb);
                }
            }
        });

        findViewById(R.id.undo).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                kanjiDrawing.undo();
            }
        });
        findViewById(R.id.clear).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                kanjiDrawing.clear();
            }
        });
        findViewById(R.id.done).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                new MatchThread(MainActivity.this, kanjiDrawing.getStrokes(),
                        KanjiInfo.MatchAlgorithm.STRICT, R.string.waitexact,
                        kanjiDrawing.getStrokes().length == 1 ? R.string.pickexact1 : R.string.pickexact,
                        R.string.fuzzy, STAGE_EXACT, false, new String[0]);
            }
        });
        findViewById(R.id.search_button).setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText e = (EditText) findViewById(R.id.input_text);
                new SearchThread(e.getText().toString(), MainActivity.this);
            }
        });
        // for alert dialog on bad search
        mHander = new Handler(new IncomingHandlerCallback());
    }


    public void finish() {
        // This is not strictly needed, but causes it to free memory
        kanjiDrawing.clear();
        super.finish();
    }

    /**
     * Called once the kanji list has been loaded so that it enables the button
     * if needed.
     */
    private void loaded() {
        KanjiDrawing.DrawnStroke[] strokes = kanjiDrawing.getStrokes();
        findViewById(R.id.done).setEnabled(strokes.length > 0);
    }

    private static LinkedList<MainActivity> waitingActivities =
            new LinkedList<MainActivity>();

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(checkQuit(data)) {
            return;
        }
        if(resultCode != RESULT_OK || data == null) {
            return;
        }

        // If a kanji was selected return it (doesn't matter which activity it
        // came from)
        String kanji = data.getStringExtra(EXTRA_KANJI);
        EditText e = (EditText) findViewById(R.id.input_text);
        e.setText(e.getText().toString() + kanji);
    }

    static boolean tryMore(Activity activity, Intent lastIntent) {
        KanjiDrawing.DrawnStroke[] strokes = KanjiDrawing.DrawnStroke.loadFromIntent(lastIntent);

        Intent intent;
        switch(lastIntent.getIntExtra(EXTRA_STAGE, 0)) {
            case STAGE_EXACT:
                // Work out which results we already showed
                String[] alreadyShown = lastIntent.getStringArrayExtra(EXTRA_MATCHES);
                if(alreadyShown.length > ResultsActivity.TOP_COUNT) {
                    String[] actuallyShown = new String[ResultsActivity.TOP_COUNT];
                    System.arraycopy(alreadyShown, 0, actuallyShown, 0,
                            ResultsActivity.TOP_COUNT);
                    alreadyShown = actuallyShown;
                }

                // Do fuzzy results, excluding those already-shown ones
                new MatchThread(activity, strokes, KanjiInfo.MatchAlgorithm.FUZZY, R.string.waitfuzzy,
                        strokes.length == 1 ? R.string.pickfuzzy1 : R.string.pickfuzzy,
                        R.string.more, STAGE_FUZZY, true, alreadyShown);
                return true;

            case STAGE_FUZZY:
                // Show next results
                intent = new Intent(lastIntent);
                intent.putExtra(EXTRA_STARTFROM, ResultsActivity.MORE_COUNT);
                intent.putExtra(EXTRA_OTHERLABEL, R.string.plusminus1);
                intent.putExtra(EXTRA_STAGE, STAGE_MOREFUZZY);
                activity.startActivityForResult(intent, 0);
                return true;

            case STAGE_MOREFUZZY:
                // Obtain +/- 1 results
                new MatchThread(activity, strokes, KanjiInfo.MatchAlgorithm.FUZZY_1OUT, R.string.waitfuzzy,
                        strokes.length == 1 ? R.string.pickfuzzy1pm1 : R.string.pickfuzzypm1,
                        R.string.more, STAGE_PLUSMINUS1, true, new String[0]);
                return true;

            case STAGE_PLUSMINUS1:
                intent = new Intent(lastIntent);
                intent.putExtra(EXTRA_STARTFROM, ResultsActivity.MORE_COUNT);
                intent.putExtra(EXTRA_OTHERLABEL, R.string.more);
                intent.putExtra(EXTRA_STAGE, STAGE_MOREPLUSMINUS1);
                activity.startActivityForResult(intent, 0);
                return true;

            case STAGE_MOREPLUSMINUS1:
                intent = new Intent(lastIntent);
                intent.putExtra(EXTRA_STARTFROM, ResultsActivity.MORE_COUNT * 2);
                intent.putExtra(EXTRA_OTHERLABEL, R.string.giveup);
                intent.putExtra(EXTRA_STAGE, STAGE_EVENMOREPLUSMINUS1);
                activity.startActivityForResult(intent, 0);
                return true;

            case STAGE_EVENMOREPLUSMINUS1:
                // Nope, give up
                break;
        }
        return false;
    }

    private boolean checkQuit(Intent intent) {
        if(intent != null && intent.getAction() != null
                && intent.getAction().equals("QUIT")) {
            quit();
            return true;
        }
        else {
            return false;
        }
    }

    private void quit() {
        Intent intent = new Intent("QUIT");
        setResult(RESULT_OK, intent);
        finish();
    }


    /**
     * Handles search error from a different thread
     */
    private class IncomingHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(Message msg) {
            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
            builder.setTitle("Error");
            builder.setMessage("The word you have searched is not a proper word");
            builder.setCancelable(true);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.cancel();
                }
            });

            AlertDialog alert = builder.create();
            alert.show();

            return true;
        }
    }

    /**
     *  Separate thread for parsing data from Jisho.org API
     */
    private class SearchThread implements Runnable {
        private String kanji;
        private Intent intent;
        private ProgressDialog progressDialog;
        private Activity activity;

        public SearchThread(String kanji, Activity activity) {
            this.kanji = kanji;
            this.activity = activity;
            progressDialog = new ProgressDialog(activity);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMessage("Loading...");
            progressDialog.setCancelable(false);
            progressDialog.setProgressNumberFormat(null);
            progressDialog.setProgressPercentFormat(null);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            progressDialog.show();
            Thread thread = new Thread(this);
            thread.start();
        }

        @Override
        public void run() {
            try {
                parseFileAndSaveToActivity(kanji);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (JSONException e) {
                e.printStackTrace();
            } finally {
                progressDialog.dismiss();
            }
        }

        private void parseFileAndSaveToActivity(String kanji) throws IOException, JSONException {
            HttpDictonaryProvider website = new HttpDictonaryProvider();
            website.setWord(kanji);

            JSONObject jsonObject = JSONReader.readJsonFromUrl(HttpDictonaryProvider.getURL().toString());

            parseDictionary(jsonObject);
        }

        /**
         * parses JSON data in the form
         *              ArrayList of Pair: -Pair.first is a list of Pair : - [Kanji]
         *                                                                 - [Pronunciation]
         *                                 -Pair.second is a list of [Definition]
         *
         * @param json
         * @throws JSONException
         */
        private void parseDictionary(JSONObject json) throws JSONException {

            JSONArray array = json.getJSONArray("data");

            if (array == null || array.length() < 1) {
                progressDialog.dismiss();
                mHander.sendEmptyMessage(0);
                return;
            }

            // List of data -> pair of Japanese, Senses

            ArrayList<Pair<ArrayList<Pair<String, String>>, ArrayList<String>>> items = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject word = array.getJSONObject(i);
                JSONArray japanese = word.getJSONArray("japanese");
                JSONArray senses = word.getJSONArray("senses");
                ArrayList<String> defn = new ArrayList<>();
                for (int k = 0; k < senses.length(); k++) {
                    JSONObject sense = senses.getJSONObject(k);
                    JSONArray arr = sense.getJSONArray("english_definitions");
                    for (int j = 0; j < arr.length(); j++) {
                        defn.add(arr.getString(j));
                    }
                }

                if (japanese != null && japanese.length() > 0
                        && defn.size() > 0) {
                    ArrayList<Pair<String, String>> key = new ArrayList<>();
                    for (int j = 0; j < japanese.length(); j++) {
                        Pair<String, String> pair;
                        if (japanese.getJSONObject(j).has("word") && japanese.getJSONObject(j).has("reading")) {
                            String w = japanese.getJSONObject(j).getString("word");
                            String r = japanese.getJSONObject(j).getString("reading");
                            pair = new Pair<>(w, r);
                        } else if (japanese.getJSONObject(j).has("reading")) {
                            String r = japanese.getJSONObject(j).getString("reading");
                            pair = new Pair<>("", r);
                        } else {
                            String w = japanese.getJSONObject(j).getString("word");
                            pair = new Pair<>(w, "");
                        }
                        key.add(pair);
                    }
                    items.add(new Pair<ArrayList<Pair<String, String>>, ArrayList<String>>(key, defn));
                }
            }

            progressDialog.dismiss();
            intent = new Intent(activity, DictionaryActivity.class);
            Bundle bundle = new Bundle();
            bundle.putSerializable("value", items);
            intent.putExtras(bundle);
            startActivity(intent);
        }
    }

    /**
     * Thread that loads the kanji list in the background.
     */
    private class LoadThread extends Thread {
        private LoadThread() {
            Log.d("Loading from MainActivi",
                    "Kanji drawing dictionary loading");
            setPriority(MIN_PRIORITY);
            // Start loading the kanji list but only if it wasn't loaded already
            synchronized(listSynch) {
                if(list==null) {
                    waitingActivities.add(MainActivity.this);
                    if (!listLoading) {
                        listLoading = true;
                        start();
                    }
                }
            }
        }

        @Override
        public void run() {
            try {
                long start = System.currentTimeMillis();
                Log.d("Loading from MainActivi",
                        "Kanji drawing dictionary loading");
                InputStream input = new MultiAssetInputStream(getAssets(),
                        new String[] { "strokes-20100823.xml.1", "strokes-20100823.xml.2" });
                KanjiList loaded = new KanjiList(input);
                synchronized(listSynch) {
                    list = loaded;
                    for(MainActivity listening : waitingActivities) {
                        final MainActivity current = listening;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                current.loaded();
                            }
                        });
                    }
                    waitingActivities = null;
                }
                long time = System.currentTimeMillis() - start;
                Log.d(MainActivity.class.getName(),
                        "Kanji drawing dictionary loaded (" + time + "ms)");
            }
            catch(IOException e) {
                Log.e(MainActivity.class.getName(), "Error loading dictionary", e);
            }
            finally {
                synchronized(listSynch) {
                    listLoading = false;
                }
            }
        }
    }

    static class MatchThread extends Thread {
        private KanjiInfo info;
        private ProgressDialog dialog;
        private KanjiInfo.MatchAlgorithm algo;
        private Intent intent;
        private KanjiList.Progress progress;
        private Activity activity;

        /**
         * @param owner Owning activity
         * @param algo Algorithm to use to do match
         * @param waitString String (R.string) to display in wait dialog
         * @param labelString String to use for activity label
         * @param otherString String to use for 'nope not that' button
         * @param stageCode Code to use for activity result
         * @param showMore Show more kanji (smaller grid)
         * @param alreadyShown Array of kanji that were already shown so don't
         *   show them again
         */
        MatchThread(Activity owner, KanjiDrawing.DrawnStroke[] strokes, KanjiInfo.MatchAlgorithm algo,
                    int waitString, int labelString, int otherString, int stageCode,
                    boolean showMore, String[] alreadyShown) {
            this.activity = owner;
            dialog = new ProgressDialog(activity);
            dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            dialog.setMessage(activity.getString(waitString));
            dialog.setCancelable(false);
            dialog.show();
            progress = new KanjiList.Progress() {
                @Override
                public void progress(final int done, final int max) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if(done == 0) {
                                dialog.setMax(max);
                            }
                            dialog.setProgress(done);
                        }
                    });
                }
            };
            this.algo = algo;

            // Build info
            info = getKanjiInfo(strokes);

            // Build intent
            intent = new Intent(activity, ResultsActivity.class);
            intent.putExtra(EXTRA_LABEL, labelString);
            intent.putExtra(EXTRA_OTHERLABEL, otherString);
            intent.putExtra(EXTRA_SHOWMORE, showMore);
            intent.putExtra(EXTRA_ALREADYSHOWN, alreadyShown);
            intent.putExtra(EXTRA_STAGE, stageCode);
            intent.putExtra(EXTRA_ALGO, algo.toString());
            KanjiDrawing.DrawnStroke.saveToIntent(intent, strokes);

            start();
        }

        public void run() {
            boolean closedDialog = false;
            try {
                final KanjiMatch[] matches = list.getTopMatches(info, algo, progress);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        dialog.dismiss();
                        String[] chars = new String[matches.length];
                        for(int i=0; i<matches.length; i++) {
                            chars[i] = matches[i].getKanji().getKanji();
                        }
                        intent.putExtra(EXTRA_MATCHES, chars);
                        activity.startActivityForResult(intent, 0);
                    }
                });
            }
            finally {
                if(!closedDialog) {
                    activity.runOnUiThread(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            dialog.dismiss();
                        }
                    });
                }
            }
        }
    }

    /**
     * Converts from drawn strokes to the KanjiInfo object that classes expect
     * @param strokes Strokes
     * @return Equivalent KanjiInfo object
     */
    static KanjiInfo getKanjiInfo(KanjiDrawing.DrawnStroke[] strokes) {
        KanjiInfo info = new KanjiInfo("?");
        for(KanjiDrawing.DrawnStroke stroke : strokes) {
            InputStroke inputStroke = new InputStroke(
                    stroke.getStartX(), stroke.getStartY(),
                    stroke.getEndX(), stroke.getEndY());
            info.addStroke(inputStroke);
        }
        info.finish();
        return info;
    }
}
