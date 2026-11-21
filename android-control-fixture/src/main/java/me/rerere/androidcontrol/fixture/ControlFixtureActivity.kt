package me.rerere.androidcontrol.fixture
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*

class ControlFixtureActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(16,48,16,36)}
        val input=EditText(this).apply {id=R.id.input_editor;hint="Enter test text";maxLines=3;minLines=3}
        val result=TextView(this).apply {id=R.id.result_text;text="Ready";maxLines=3;setTextIsSelectable(true)}
        body.addView(input,LinearLayout.LayoutParams(-1,160))
        body.addView(Button(this).apply {id=R.id.apply_button;text="Apply";setOnClickListener {result.text="Applied: "+input.text.toString();input.clearFocus()};setOnLongClickListener {result.text="Long pressed";true} })
        body.addView(result,LinearLayout.LayoutParams(-1,160))
        // Test-only animation exercises actual virtual-display buffer delivery, not screenshot polling.
        val animation=PreviewAnimationView(this).apply {visibility=View.GONE}
        body.addView(Button(this).apply {id=R.id.animate_preview_button;text="Animate preview";setOnClickListener {
            val running=animation.visibility!=View.VISIBLE
            animation.visibility=if(running)View.VISIBLE else View.GONE
            animation.running=running
            if(running)animation.postInvalidateOnAnimation()
            text=if(running)"Stop preview animation" else "Animate preview"
        }})
        body.addView(animation,LinearLayout.LayoutParams(-1,180))
        body.addView(SeekBar(this).apply {id=R.id.drag_slider;max=100;setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {if(fromUser)result.text="Slider: $progress"}
            override fun onStartTrackingTouch(seekBar:SeekBar?)=Unit
            override fun onStopTrackingTouch(seekBar:SeekBar?)=Unit
        })})
        repeat(16) {body.addView(TextView(this).apply {text="Scrollable item ${it+1}";textSize=20f;setPadding(8,24,8,24)})}
        body.addView(Button(this).apply {id=R.id.bottom_target;text="Bottom target";setOnClickListener {result.text="Bottom reached";text="Bottom reached"} })
        val password=EditText(this).apply {id=R.id.password_editor;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText("fixture-secret-never-export");visibility=View.GONE}
        body.addView(Button(this).apply {id=R.id.show_password_button;text="Show secure field";setOnClickListener {password.visibility=View.VISIBLE;window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)} })
        body.addView(password)
        setContentView(ScrollView(this).apply {id=R.id.scroll_container;isFillViewport=true;addView(body)})
    }
}

private class PreviewAnimationView(context:Context):View(context) {
    var running=false
    private var frame=0
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {contentDescription="Native preview animation fixture"}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(if(frame%2==0)Color.rgb(18,104,188) else Color.rgb(225,92,28))
        paint.color=Color.WHITE
        canvas.drawCircle((frame*11 % width.coerceAtLeast(1)).toFloat(),height/2f,24f,paint)
        frame++
        if(running && isAttachedToWindow)postInvalidateOnAnimation()
    }
    override fun onDetachedFromWindow() {running=false;super.onDetachedFromWindow()}
}
