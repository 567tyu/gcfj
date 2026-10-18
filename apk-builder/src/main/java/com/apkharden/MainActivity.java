package com.apkharden;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class MainActivity extends Activity {
    private EditText inputPathText;
    private EditText outputPathText;
    private TextView statusText;
    private Button hardenButton;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        inputPathText = findViewById(R.id.inputPath);
        outputPathText = findViewById(R.id.outputPath);
        statusText = findViewById(R.id.statusText);
        hardenButton = findViewById(R.id.hardenButton);
        
        inputPathText.setText("/sdcard/input.apk");
        outputPathText.setText("/sdcard/output-hardened.apk");
        
        hardenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String inputPath = inputPathText.getText().toString();
                final String outputPath = outputPathText.getText().toString();
                
                if (inputPath.isEmpty() || outputPath.isEmpty()) {
                    Toast.makeText(MainActivity.this, "请输入输入和输出路径", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                statusText.setText("正在加固...");
                hardenButton.setEnabled(false);
                
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            ApkHardener hardener = new ApkHardener();
                            final String result = hardener.hardenApk(inputPath, outputPath);
                            
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.setText(result);
                                    hardenButton.setEnabled(true);
                                    Toast.makeText(MainActivity.this, "加固完成!", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (final Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.setText("错误: " + e.getMessage());
                                    hardenButton.setEnabled(true);
                                    Toast.makeText(MainActivity.this, "加固失败", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                }).start();
            }
        });
    }
}
