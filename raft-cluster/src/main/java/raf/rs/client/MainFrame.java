package raf.rs.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.AddCommand;
import raf.rs.RPC.ClientMessageRes;
import raf.rs.RPC.Command;
import raf.rs.RPC.RAFTGrpc;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private String username = "ai_testzzz";
    private final int port;
    public static int leaderPort;
    private final Random r;

    private static MainFrame instance;

    // gRPC — volatile because redirect can swap them from a background thread
    private volatile ManagedChannel channel;
    private volatile RAFTGrpc.RAFTBlockingStub stub;
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);

    // UI refs needed across methods
    private JPanel chatPanel;
    private JScrollPane chatScroll;
    private JLabel currentUsernameLabel;
    private JTextField messageField;
    private JButton sendBtn;
    private JButton skullBtn;
    private JButton wowBtn;

    // ── Colors ───────────────────────────────────────────────────────────────
    private static final Color BG_DARK      = new Color(15, 15, 25);
    private static final Color BG_PANEL     = new Color(22, 22, 38);
    private static final Color BG_INPUT     = new Color(30, 30, 50);
    private static final Color ACCENT       = new Color(99, 102, 241);
    private static final Color ACCENT_HOVER = new Color(129, 132, 255);
    private static final Color MSG_BUBBLE   = new Color(35, 35, 60);
    private static final Color TEXT_PRIMARY = new Color(230, 230, 255);
    private static final Color TEXT_MUTED   = new Color(130, 130, 170);
    private static final Color BORDER_COLOR = new Color(45, 45, 75);

    private MainFrame() throws HeadlessException {
        this.leaderPort = 9000;
        this.port       = 8999;
        this.r          = new Random();

        // gRPC — initial channel (may be redirected to leader later)
        this.channel = ManagedChannelBuilder
                .forAddress("localhost", leaderPort)
                .usePlaintext()
                .build();
        this.stub = RAFTGrpc.newBlockingStub(this.channel);

        // Frame
        this.setTitle("💬 Chat Client");
        this.setSize(860, 580);
        this.setMinimumSize(new Dimension(600, 400));
        this.setLocationRelativeTo(null);
        this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        this.getContentPane().setBackground(BG_DARK);
        this.setLayout(new BorderLayout());

        this.add(buildTopBar(),    BorderLayout.NORTH);
        this.add(buildChatArea(),  BorderLayout.CENTER);
        this.add(buildBottomBar(), BorderLayout.SOUTH);

        appendMessage("system", "Welcome to the chat! 👋", true);
    }

    // =========================================================================
    // gRPC
    // =========================================================================

    /**
     * Sends a ClientMessageReq over gRPC on a background thread.
     * If the node is not the leader, res.getSuccess()==false and
     * res.getInfo() contains the leader port. We reconnect and retry once.
     *
     * @param messageContent Either free text OR the keyword "skull" / "wow".
     *                       These two are NEVER mixed - enforced by the UI.
     */
    private void sendGrpc(String messageContent) {
        String ts = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        AddCommand req = AddCommand.newBuilder()
                .setMessageId(messageIdCounter.getAndIncrement())
                .setUser(username)
                .setMessage(messageContent)
                .setTimestamp(ts)
                .build();
        Command command = Command.newBuilder().setAddCommand(req).build();

        new Thread(() -> {
            try {
                ClientMessageRes res = stub.clientCommand(command);
                log.info("Server ack - success={} info={}", res.getSuccess(), res.getInfo());

                if (!res.getSuccess()) {
                    // Not the leader - res.getInfo() has the leader port
                    int newLeaderPort = Integer.parseInt(res.getInfo());
                    log.info("Redirecting to leader at port {}", newLeaderPort);
                    redirectToLeader(newLeaderPort);

                    // Retry on the real leader
                    ClientMessageRes retryRes = stub.clientCommand(command);
                    log.info("Retry ack - success={} info={}", retryRes.getSuccess(), retryRes.getInfo());

                    if (!retryRes.getSuccess()) {
                        SwingUtilities.invokeLater(() ->
                                appendMessage("system", "Server rejected: " + retryRes.getInfo(), true));
                    }
                }

            } catch (StatusRuntimeException e) {
                log.error("gRPC error: {}", e.getStatus());
                SwingUtilities.invokeLater(() ->
                        appendMessage("system", "Connection error: " + e.getStatus().getCode(), true));
            }
        }).start();
    }

    /**
     * Tears down the current channel and builds a fresh one pointing at
     * the new leader port. Synchronized so concurrent sends don't race.
     */
    private synchronized void redirectToLeader(int newPort) {
        channel.shutdownNow();
        leaderPort = newPort;
        channel = ManagedChannelBuilder
                .forAddress("localhost", leaderPort)
                .usePlaintext()
                .build();
        stub = RAFTGrpc.newBlockingStub(channel);
        log.info("Channel rebuilt -> localhost:{}", leaderPort);
    }

    // =========================================================================
    // Send logic
    // =========================================================================

    /** Send button / Enter — plain text only, emoji buttons must be disabled. */
    private void handleTextSend() {
        String text = messageField.getText().trim();
        if (text.isEmpty()) return;
        appendMessage(username, text, false);
        sendGrpc(text);
        messageField.setText("");
        setEmojiEnabled(true);   // field is now empty → unlock emoji
    }

    /**
     * Emoji button clicked — sends immediately, no text goes along.
     *
     * @param keyword  "skull" or "wow"  — what the server will receive
     * @param display  "💀"   or "😮"   — what the chat bubble shows
     */
    private void handleEmojiSend(String keyword, String display) {
        appendMessage(username, display, false);
        sendGrpc(keyword);
    }

    /**
     * Mutex: while the user is typing, emoji buttons are locked.
     * As soon as the field is cleared the buttons come back.
     */
    private void setEmojiEnabled(boolean enabled) {
        skullBtn.setEnabled(enabled);
        wowBtn.setEnabled(enabled);
        skullBtn.setToolTipText(enabled ? "Skull — sends immediately" : "Clear text first");
        wowBtn.setToolTipText(enabled   ? "Wow — sends immediately"   : "Clear text first");
    }

    // =========================================================================
    // UI builders
    // =========================================================================

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BG_PANEL);
        bar.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_COLOR),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));

        // Left — username input
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        JLabel lbl = new JLabel("Username:");
        lbl.setForeground(TEXT_MUTED);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JTextField usernameField = new JTextField(13);
        styleTextField(usernameField);
        JButton setBtn = buildTextButton("Set");
        setBtn.addActionListener(_ -> {
            String val = usernameField.getText().trim();
            if (!val.isEmpty()) {
                this.username = val;
                usernameField.setText("");
                currentUsernameLabel.setText("● " + this.username);
            }
        });
        left.add(lbl);
        left.add(usernameField);
        left.add(setBtn);

        // Right — current user label + refresh
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        currentUsernameLabel = new JLabel("● " + username);
        currentUsernameLabel.setForeground(ACCENT_HOVER);
        currentUsernameLabel.setFont(new Font("Monospaced", Font.BOLD, 13));
        JButton refreshBtn = buildTextButton("⟳ Refresh");
        refreshBtn.addActionListener(_ -> { /* TODO: pull latest messages from server */ });
        right.add(currentUsernameLabel);
        right.add(refreshBtn);

        bar.add(left,  BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JScrollPane buildChatArea() {
        chatPanel = new JPanel();
        chatPanel.setLayout(new BoxLayout(chatPanel, BoxLayout.Y_AXIS));
        chatPanel.setBackground(BG_DARK);
        chatPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        chatScroll = new JScrollPane(chatPanel);
        chatScroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, BORDER_COLOR));
        chatScroll.getViewport().setBackground(BG_DARK);
        chatScroll.getVerticalScrollBar().setUnitIncrement(16);
        chatScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        styleScrollBar(chatScroll.getVerticalScrollBar());
        return chatScroll;
    }

    private JPanel buildBottomBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(BG_PANEL);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        // Emoji panel — left side
        JPanel emojiPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        emojiPanel.setOpaque(false);

        skullBtn = buildEmojiButton("💀", "Skull — sends immediately");
        wowBtn   = buildEmojiButton("😮", "Wow — sends immediately");

        // Emoji: clear field, send immediately
        skullBtn.addActionListener(_ -> {
            messageField.setText("");
            handleEmojiSend("skull", "💀");
        });
        wowBtn.addActionListener(_ -> {
            messageField.setText("");
            handleEmojiSend("wow", "😮");
        });

        emojiPanel.add(skullBtn);
        emojiPanel.add(wowBtn);

        // Text field — center
        messageField = new JTextField();
        styleTextField(messageField);
        messageField.setFont(new Font("SansSerif", Font.PLAIN, 14));
        messageField.setPreferredSize(new Dimension(0, 36));
        messageField.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) handleTextSend();
            }
            @Override public void keyReleased(KeyEvent e) {
                // Lock emoji buttons as soon as typing starts; unlock when cleared
                setEmojiEnabled(messageField.getText().trim().isEmpty());
            }
        });

        // Send button — right
        sendBtn = buildSendButton();
        sendBtn.addActionListener(_ -> handleTextSend());

        bar.add(emojiPanel,   BorderLayout.WEST);
        bar.add(messageField, BorderLayout.CENTER);
        bar.add(sendBtn,      BorderLayout.EAST);
        return bar;
    }

    // =========================================================================
    // Chat bubble
    // =========================================================================

    private void appendMessage(String sender, String text, boolean isSystem) {
        JPanel bubble = new JPanel();
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBackground(isSystem ? new Color(30, 60, 50) : MSG_BUBBLE);
        bubble.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(
                        isSystem ? new Color(50, 120, 90) : BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        bubble.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (!isSystem) {
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            JLabel header = new JLabel(sender + "  " + time);
            header.setForeground(ACCENT_HOVER);
            header.setFont(new Font("Monospaced", Font.BOLD, 11));
            bubble.add(header);
            bubble.add(Box.createVerticalStrut(4));
        }

        JLabel msg = new JLabel(
                "<html><body style='width:480px'>" + escapeHtml(text) + "</body></html>");
        msg.setForeground(isSystem ? new Color(160, 230, 190) : TEXT_PRIMARY);
        msg.setFont(new Font("SansSerif", Font.PLAIN, 14));
        bubble.add(msg);

        chatPanel.add(bubble);
        chatPanel.add(Box.createVerticalStrut(8));
        chatPanel.revalidate();

        SwingUtilities.invokeLater(() -> {
            JScrollBar sb = chatScroll.getVerticalScrollBar();
            sb.setValue(sb.getMaximum());
        });
    }

    // =========================================================================
    // Style helpers
    // =========================================================================

    private void styleTextField(JTextField f) {
        f.setBackground(BG_INPUT);
        f.setForeground(TEXT_PRIMARY);
        f.setCaretColor(ACCENT_HOVER);
        f.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        f.setFont(new Font("Monospaced", Font.PLAIN, 13));
    }

    private JButton buildTextButton(String label) {
        JButton btn = new JButton(label);
        btn.setBackground(BG_INPUT);
        btn.setForeground(TEXT_MUTED);
        btn.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setFont(new Font("Monospaced", Font.PLAIN, 12));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setForeground(TEXT_PRIMARY); }
            public void mouseExited(MouseEvent e)  { btn.setForeground(TEXT_MUTED); }
        });
        return btn;
    }

    private JButton buildEmojiButton(String emoji, String tooltip) {
        JButton btn = new JButton(emoji);
        btn.setToolTipText(tooltip);
        btn.setBackground(BG_INPUT);
        btn.setForeground(TEXT_PRIMARY);
        btn.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 18));
        btn.setPreferredSize(new Dimension(52, 36));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                if (btn.isEnabled()) btn.setBackground(new Color(45, 45, 70));
            }
            public void mouseExited(MouseEvent e) { btn.setBackground(BG_INPUT); }
        });
        return btn;
    }

    private JButton buildSendButton() {
        JButton btn = new JButton("Send →");
        btn.setBackground(ACCENT);
        btn.setForeground(Color.WHITE);
        btn.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 85, 210), 1, true),
                BorderFactory.createEmptyBorder(4, 18, 4, 18)));
        btn.setFocusPainted(false);
        btn.setFont(new Font("SansSerif", Font.BOLD, 13));
        btn.setPreferredSize(new Dimension(100, 36));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) { btn.setBackground(ACCENT_HOVER); }
            public void mouseExited(MouseEvent e)  { btn.setBackground(ACCENT); }
        });
        return btn;
    }

    private void styleScrollBar(JScrollBar bar) {
        bar.setBackground(BG_DARK);
        bar.setPreferredSize(new Dimension(6, 0));
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                this.thumbColor = new Color(70, 70, 110);
                this.trackColor = BG_DARK;
            }
            @Override protected JButton createDecreaseButton(int o) { return zero(); }
            @Override protected JButton createIncreaseButton(int o) { return zero(); }
            private JButton zero() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }
        });
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // =========================================================================
    // Singleton
    // =========================================================================

    public static MainFrame getInstance() {
        if (instance == null) instance = new MainFrame();
        return instance;
    }
}