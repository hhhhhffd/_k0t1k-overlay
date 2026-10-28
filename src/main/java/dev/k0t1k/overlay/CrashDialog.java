package dev.k0t1k.overlay;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * The crash window. The overlay starts it in a JVM of its own from the game's shutdown hook when the
 * game wrote a crash report, so it stays up after the game is gone. It shows the reason and offers to
 * upload the report (plus the overlay's notes from the dying game: a thread dump and uncaught
 * exceptions) to mclo.gs for support. Nothing leaves the computer unless the player clicks.
 *
 * <p>Arguments: {@code lang report bundle supportUrl}.
 */
public final class CrashDialog {
    private static final Color BG = new Color(0x13101c);
    private static final Color RAISED = new Color(0x1b1728);
    private static final Color FG = new Color(0xece8f6);
    private static final Color MUTED = new Color(0xa199ba);
    private static final Color ACCENT = new Color(0xa259f7);

    private CrashDialog() {
    }

    public static void main(String[] args) throws IOException {
        boolean ru = args.length > 0 && args[0].startsWith("ru");
        Path report = Paths.get(args[1]);
        Path bundle = args.length > 2 && !args[2].isEmpty() ? Paths.get(args[2]) : null;
        String support = args.length > 3 ? args[3] : "";
        String text = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        String extra = bundle != null && Files.exists(bundle) ? new String(Files.readAllBytes(bundle), StandardCharsets.UTF_8) : "";
        SwingUtilities.invokeLater(() -> show(ru, report, text, extra, support));
    }

    private static void show(boolean ru, Path report, String text, String extra, String support) {
        JFrame frame = new JFrame(ru ? "Minecraft вылетел — _k0t1k" : "Minecraft crashed — _k0t1k");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        JLabel title = new JLabel(ru ? "Игра вылетела" : "The game crashed");
        title.setForeground(FG);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        JLabel reason = new JLabel("<html>" + escape(reason(text)) + "</html>");
        reason.setForeground(MUTED);
        head.add(title, BorderLayout.NORTH);
        head.add(reason, BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);

        JTextArea area = new JTextArea(text + (extra.isEmpty() ? "" : "\n\n" + extra));
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(RAISED);
        area.setForeground(FG);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(0x2b2542)));
        scroll.setPreferredSize(new Dimension(820, 420));
        root.add(scroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JLabel status = new JLabel(" ");
        status.setForeground(MUTED);
        JButton upload = button(ru ? "Отправить в сапорт" : "Send to support", true);
        upload.addActionListener(e -> {
            upload.setEnabled(false);
            status.setText(ru ? "Загружаю отчёт на mclo.gs…" : "Uploading the report to mclo.gs…");
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws IOException {
                    return uploadToMclogs(text + (extra.isEmpty() ? "" : "\n\n" + extra));
                }

                @Override
                protected void done() {
                    try {
                        String url = get();
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url), null);
                        status.setText((ru ? "Ссылка скопирована: " : "Link copied: ") + url);
                        if (!support.isEmpty()) {
                            browse(support);
                        }
                    } catch (Exception ex) {
                        status.setText((ru ? "Не удалось загрузить: " : "Upload failed: ") + ex.getMessage());
                        upload.setEnabled(true);
                    }
                }
            }.execute();
        });
        JButton copy = button(ru ? "Скопировать отчёт" : "Copy report", false);
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            status.setText(ru ? "Отчёт скопирован" : "Report copied");
        });
        JButton folder = button(ru ? "Открыть папку" : "Open folder", false);
        folder.addActionListener(e -> {
            try {
                Desktop.getDesktop().open(report.getParent().toFile());
            } catch (IOException | RuntimeException ex) {
                status.setText(ex.getMessage());
            }
        });
        JButton close = button(ru ? "Закрыть" : "Close", false);
        close.addActionListener(e -> frame.dispose());
        buttons.add(folder);
        buttons.add(copy);
        buttons.add(upload);
        buttons.add(close);

        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(buttons, BorderLayout.SOUTH);
        JLabel privacy = new JLabel(ru ? "Отчёт уходит на mclo.gs только по кнопке «Отправить в сапорт»."
                : "The report goes to mclo.gs only when you click Send to support.");
        privacy.setForeground(new Color(0x6d6588));
        bottom.add(privacy, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setAlwaysOnTop(true);
        frame.setVisible(true);
        frame.toFront();
        frame.setAlwaysOnTop(false);
    }

    private static JButton button(String label, boolean primary) {
        JButton button = new JButton(label);
        button.setFocusPainted(false);
        button.setBackground(primary ? ACCENT : RAISED);
        button.setForeground(primary ? Color.WHITE : FG);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return button;
    }

    /** "Description: ..." and the first exception line of a Minecraft crash report. */
    static String reason(String report) {
        String description = "";
        String exception = "";
        for (String line : report.split("\n")) {
            String trimmed = line.trim();
            if (description.isEmpty() && trimmed.startsWith("Description:")) {
                description = trimmed.substring("Description:".length()).trim();
            } else if (!description.isEmpty() && exception.isEmpty() && !trimmed.isEmpty()) {
                exception = trimmed;
                break;
            }
        }
        String reason = description.isEmpty() ? "" : description + (exception.isEmpty() ? "" : " — " + exception);
        return reason.length() > 300 ? reason.substring(0, 300) + "…" : reason;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Uploads to mclo.gs and returns the page address. */
    static String uploadToMclogs(String content) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.mclo.gs/1/log").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] body = ("content=" + URLEncoder.encode(content, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        String response;
        try (InputStream in = connection.getResponseCode() < 400 ? connection.getInputStream() : connection.getErrorStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; in != null && (n = in.read(buffer)) > 0; ) {
                bytes.write(buffer, 0, n);
            }
            response = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        Matcher url = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
        if (!url.find()) {
            throw new IOException("mclo.gs answered " + connection.getResponseCode() + ": " + response);
        }
        return url.group(1).replace("\\/", "/");
    }

    private static void browse(String url) {
        try {
            if (url.startsWith("https://") && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("[k0t1k-overlay] could not open " + url + ": " + e);
        }
    }
}
