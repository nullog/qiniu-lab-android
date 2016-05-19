package com.qiniu.qiniulab.activity.upload;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.ipaulpro.afilechooser.utils.FileUtils;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UpProgressHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.android.storage.UploadOptions;
import com.qiniu.android.utils.AsyncRun;
import com.qiniu.qiniulab.R;
import com.qiniu.qiniulab.config.QiniuLabConfig;
import com.qiniu.qiniulab.utils.Tools;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

public class SimpleUploadEnableCrc32CheckActivity extends AppCompatActivity {
    private static final int REQUEST_CODE = 8090;
    private SimpleUploadEnableCrc32CheckActivity context;
    private EditText uploadFileKeyEditText;
    private TextView uploadLogTextView;
    private CheckBox uploadEnableCrc32CheckBox;
    private LinearLayout uploadStatusLayout;
    private ProgressBar uploadProgressBar;
    private TextView uploadSpeedTextView;
    private TextView uploadFileLengthTextView;
    private TextView uploadPercentageTextView;
    private UploadManager uploadManager;
    private long uploadLastTimePoint;
    private long uploadLastOffset;
    private long uploadFileLength;
    private String uploadFilePath;

    public SimpleUploadEnableCrc32CheckActivity() {
        this.context = this;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.simple_upload_enable_crc32_check_activity);
        this.uploadEnableCrc32CheckBox = (CheckBox) this
                .findViewById(R.id.simple_upload_enable_crc32_checkbox);
        this.uploadFileKeyEditText = (EditText) this
                .findViewById(R.id.simple_upload_enable_crc32_check_file_key);
        this.uploadProgressBar = (ProgressBar) this
                .findViewById(R.id.simple_upload_enable_crc32_check_upload_progressbar);
        this.uploadProgressBar.setMax(100);
        this.uploadStatusLayout = (LinearLayout) this
                .findViewById(R.id.simple_upload_enable_crc32_check_status_layout);
        this.uploadSpeedTextView = (TextView) this
                .findViewById(R.id.simple_upload_enable_crc32_check_upload_speed_textview);
        this.uploadFileLengthTextView = (TextView) this
                .findViewById(R.id.simple_upload_enable_crc32_check_upload_file_length_textview);
        this.uploadPercentageTextView = (TextView) this
                .findViewById(R.id.simple_upload_enable_crc32_check_upload_percentage_textview);
        this.uploadStatusLayout.setVisibility(LinearLayout.INVISIBLE);
        this.uploadLogTextView = (TextView) this
                .findViewById(R.id.simple_upload_enable_crc32_check_log_textview);

    }

    public void selectUploadFile(View view) {
        Intent target = FileUtils.createGetContentIntent();
        Intent intent = Intent.createChooser(target,
                this.getString(R.string.choose_file));
        try {
            this.startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE:
                // If the file selection was successful
                if (resultCode == RESULT_OK) {
                    if (data != null) {
                        // Get the URI of the selected file
                        final Uri uri = data.getData();
                        try {
                            // Get the file path from the URI
                            final String path = FileUtils.getPath(this, uri);
                            this.uploadFilePath = path;
                            this.clearLog();
                            this.writeLog(context
                                    .getString(R.string.qiniu_select_upload_file)
                                    + path);
                        } catch (Exception e) {
                            Toast.makeText(
                                    context,
                                    context.getString(R.string.qiniu_get_upload_file_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public void uploadFile(View view) {
        if (this.uploadFilePath == null) {
            return;
        }
        //从业务服务器获取上传凭证
        new Thread(new Runnable() {
            @Override
            public void run() {
                final OkHttpClient httpClient = new OkHttpClient();
                Request req = new Request.Builder().url(QiniuLabConfig.makeUrl(
                        QiniuLabConfig.REMOTE_SERVICE_SERVER,
                        QiniuLabConfig.SIMPLE_UPLOAD_ENABLE_CRC32_CHECK_PATH)).method("GET", null).build();
                Response resp = null;
                try {
                    resp = httpClient.newCall(req).execute();
                    JSONObject jsonObject = new JSONObject(resp.body().string());
                    String uploadToken = jsonObject.getString("uptoken");
                    writeLog(context
                            .getString(R.string.qiniu_get_upload_token)
                            + uploadToken);
                    upload(uploadToken);
                } catch (IOException e) {
                    AsyncRun.run(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(
                                    context,
                                    context.getString(R.string.qiniu_get_upload_token_failed),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    writeLog(context
                            .getString(R.string.qiniu_get_upload_token_failed)
                            + resp.toString());
                } catch (JSONException e) {
                    writeLog(context.getString(R.string.qiniu_get_upload_token_failed));
                    writeLog("StatusCode:" + resp.code());
                    if (resp != null) {
                        writeLog("Response:" + resp.toString());
                    }
                    writeLog("Exception:" + e.getMessage());
                } finally {
                    if (resp != null) {
                            resp.body().close();
                    }
                }
            }
        }).start();

    }

    private void upload(String uploadToken) {
        if (this.uploadManager == null) {
            this.uploadManager = new UploadManager();
        }
        File uploadFile = new File(this.uploadFilePath);
        String uploadFileKey = this.uploadFileKeyEditText.getText().toString();
        boolean enableCrc32Check = this.uploadEnableCrc32CheckBox.isChecked();
        UploadOptions uploadOptions = new UploadOptions(null, null,
                enableCrc32Check, new UpProgressHandler() {
            @Override
            public void progress(String key, double percent) {
                updateStatus(percent);
            }
        }, null);
        final long startTime = System.currentTimeMillis();
        final long fileLength = uploadFile.length();
        this.uploadFileLength = fileLength;
        this.uploadLastTimePoint = startTime;
        this.uploadLastOffset = 0;

        AsyncRun.run(new Runnable() {
            @Override
            public void run() {
                // prepare status
                uploadPercentageTextView.setText("0 %");
                uploadSpeedTextView.setText("0 KB/s");
                uploadFileLengthTextView.setText(Tools.formatSize(fileLength));
                uploadStatusLayout.setVisibility(LinearLayout.VISIBLE);
            }
        });
        writeLog(context.getString(R.string.qiniu_upload_file) + "...");
        this.uploadManager.put(uploadFile, uploadFileKey, uploadToken,
                new UpCompletionHandler() {
                    @Override
                    public void complete(String key, ResponseInfo respInfo,
                                         JSONObject jsonData) {
                        AsyncRun.run(new Runnable() {
                            @Override
                            public void run() {
                                // reset status
                                uploadStatusLayout
                                        .setVisibility(LinearLayout.INVISIBLE);
                                uploadProgressBar.setProgress(0);
                            }
                        });

                        long lastMillis = System.currentTimeMillis()
                                - startTime;
                        if (respInfo.isOK()) {
                            try {
                                String fileKey = jsonData.getString("key");
                                String fileHash = jsonData.getString("hash");
                                writeLog("File Size: "
                                        + Tools.formatSize(uploadFileLength));
                                writeLog("File Key: " + fileKey);
                                writeLog("File Hash: " + fileHash);
                                writeLog("Last Time: "
                                        + Tools.formatMilliSeconds(lastMillis));
                                writeLog("Average Speed: "
                                        + Tools.formatSpeed(fileLength,
                                        lastMillis));
                                writeLog("X-Reqid: " + respInfo.reqId);
                                writeLog("X-Via: " + respInfo.xvia);
                                writeLog("--------------------------------");
                            } catch (JSONException e) {
                                AsyncRun.run(new Runnable() {
                                    @Override
                                    public void run() {
                                        Toast.makeText(
                                                context,
                                                context.getString(R.string.qiniu_upload_file_response_parse_error),
                                                Toast.LENGTH_LONG).show();
                                    }
                                });

                                writeLog(context
                                        .getString(R.string.qiniu_upload_file_response_parse_error));
                                if (jsonData != null) {
                                    writeLog(jsonData.toString());
                                }
                                writeLog("--------------------------------");
                            }
                        } else {
                            AsyncRun.run(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(
                                            context,
                                            context.getString(R.string.qiniu_upload_file_failed),
                                            Toast.LENGTH_LONG).show();
                                }
                            });

                            writeLog(respInfo.toString());
                            if (jsonData != null) {
                                writeLog(jsonData.toString());
                            }
                            writeLog("--------------------------------");
                        }
                    }

                }, uploadOptions);
    }

    private void updateStatus(final double percentage) {
        long now = System.currentTimeMillis();
        long deltaTime = now - uploadLastTimePoint;
        long currentOffset = (long) (percentage * uploadFileLength);
        long deltaSize = currentOffset - uploadLastOffset;
        if (deltaTime <= 100) {
            return;
        }

        final String speed = Tools.formatSpeed(deltaSize, deltaTime);
        // update
        uploadLastTimePoint = now;
        uploadLastOffset = currentOffset;

        AsyncRun.run(new Runnable() {
            @Override
            public void run() {
                int progress = (int) (percentage * 100);
                uploadProgressBar.setProgress(progress);
                uploadPercentageTextView.setText(progress + " %");
                uploadSpeedTextView.setText(speed);
            }
        });
    }

    private void clearLog() {
        this.uploadLogTextView.setText("");
    }

    private void writeLog(final String msg) {
        AsyncRun.run(new Runnable() {
            @Override
            public void run() {
                uploadLogTextView.append(msg);
                uploadLogTextView.append("\r\n");
            }
        });
    }
}
