package raf.rs.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raf.rs.RPC.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

import java.time.LocalDateTime;
import java.util.Random;

public class MainFrame extends JFrame {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);
    private String messageText;
    private String username = "ai_testzzz";
    private LocalDateTime timeSent;
    private final int port;
    public static int leaderPort;
    private int gifId;
    private int messageId;
    private Random r;

    private static MainFrame instance;

    JLabel allMessages;

    private MainFrame() throws HeadlessException {
        this.leaderPort = 9000;
        this.port = 8999;
        this.r = new Random();


        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090).usePlaintext().build();
        RAFTGrpc.RAFTBlockingStub stub = RAFTGrpc.newBlockingStub(channel);

        this.setSize(800, 500);
        this.setLocationRelativeTo(this);
        this.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);


        // Panel settings
        BorderLayout borderLayout = new BorderLayout();
        this.setLayout(borderLayout);
        JPanel botPanel = new JPanel();
        this.add(botPanel, BorderLayout.SOUTH);
        JPanel topPanel = new JPanel();
        this.add(topPanel, BorderLayout.NORTH);
        JPanel centerPanel = new JPanel();
        this.add(centerPanel, BorderLayout.CENTER);


        // Top
        topPanel.setLayout(new BorderLayout(0, 10));
        topPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));

        JLabel insertUsernameLabel = new JLabel("Insert username:");
        JLabel currentUsernameLabel = new JLabel("Current username: " + username);

        JTextField usernameField = new JTextField(15);
        JButton setUsernameButton = new JButton("Set username");
        setUsernameButton.addActionListener(_ -> {
            this.username = usernameField.getText();
            usernameField.setText("");
            currentUsernameLabel.setText("Current username: " + this.username);
        });
        JButton getLeader = new JButton("Refresh");
        getLeader.addActionListener(_ -> {

        });


        leftPanel.add(insertUsernameLabel);
        leftPanel.add(usernameField);
        leftPanel.add(setUsernameButton);

        rightPanel.add(currentUsernameLabel);
        rightPanel.add(getLeader);


        topPanel.add(leftPanel, BorderLayout.WEST);
        topPanel.add(rightPanel, BorderLayout.EAST);

        // Center
        allMessages = new JLabel("gas");
        allMessages.setOpaque(true);
        allMessages.setBackground(new Color(20, 15,45));
        Font f = new Font("Arial", Font.PLAIN, 14);
        allMessages.setFont(f);
        allMessages.setHorizontalAlignment(SwingConstants.LEFT);
        allMessages.setVerticalAlignment(SwingConstants.TOP);
        allMessages.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        allMessages.setPreferredSize(new Dimension(400, 400));
        allMessages.setForeground(Color.white);
        centerPanel.add(allMessages);

        // Bot panel
        JTextField messageTextField = new JTextField();
        messageTextField.setPreferredSize(new Dimension(200, 30));
        messageTextField.addKeyListener(new KeyListener() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == KeyEvent.VK_ENTER) {

                }
            }

            @Override
            public void keyPressed(KeyEvent e) {

            }

            @Override
            public void keyReleased(KeyEvent e) {

            }
        });
        botPanel.add(messageTextField);
        JButton emoji1 = new JButton("XD");
        emoji1.setPreferredSize(new Dimension(100, 30));
        botPanel.add(emoji1);
        JButton emoji2 = new JButton("Dead ahh");
        emoji2.setPreferredSize(new Dimension(100, 30));
        botPanel.add(emoji2);


    }

    public void addText(String s) {
        this.allMessages.setText(allMessages.getText() + "/n" + s);
    }

    public static MainFrame getInstance() {
        if (instance == null) {
            instance = new MainFrame();
        }
        return instance;
    }
}
