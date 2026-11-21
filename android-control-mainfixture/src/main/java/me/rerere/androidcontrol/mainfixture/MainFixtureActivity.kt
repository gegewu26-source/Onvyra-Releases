package me.rerere.androidcontrol.mainfixture
import android.app.Activity
import android.os.Bundle
import android.widget.*
class MainFixtureActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {super.onCreate(savedInstanceState);setContentView(LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;setPadding(20,60,20,20)
        addView(TextView(context).apply {text="MAIN_SCREEN_UNCHANGED";textSize=22f})
        addView(EditText(context).apply {setText("User can keep typing here");contentDescription="MAIN_EDITOR"})
        addView(Button(context).apply {text="MAIN_COUNTER_0";var count=0;setOnClickListener {count++;text="MAIN_COUNTER_$count"}})
    })}
}
