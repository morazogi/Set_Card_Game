package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;
    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score=0;
    private BlockingQueue<Integer> tokens = new ArrayBlockingQueue<>(3);
    private Dealer dealer;
    public long milsToWait=0;
    public long nextFreezeTimeUpdate =0;
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer=dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            synchronized (this) {
                while (tokens.size() == 3) {//in case of penalty for not a set
                    milsToWait=-1;
                    try {
                        wait();
                    } catch (InterruptedException ignore) {}
                }
            }
            synchronized (this) {
                while (tokens.size() < 3) {//loop that waits for 3 tokens
                    milsToWait=-1;
                    try {
                        wait();
                    } catch (InterruptedException ignored) {}
                }
            }
            milsToWait=0;
            dealer.addCheck(this.id);
                try {//dealer checking and we wait
                    synchronized (this) {
                    notifyAll();
                    while (milsToWait ==0)
                        wait();
                }
                    synchronized (this) {
                        if (milsToWait > 0)
                            Thread.sleep(milsToWait);
                        while (true)
                            wait();
                    }
                } catch (InterruptedException ignored) {}
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        //note : no
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                while(tokens.size() <3) {
                    int rnd = (int) (Math.random() * ((double) env.config.tableSize));
                    this.keyPressed(rnd);
                }
                try {
                    synchronized (this) { wait(); }
                } catch (InterruptedException ignored) {}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (table.slotToCard[slot] != null) {
            if (milsToWait == -1) {
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.contains(slot)) {
                        tokens.remove(slot);
                        table.removeToken(id, slot);
                        synchronized (this) {
                            playerThread.interrupt();
                        }
                        return;
                    }
                }
                if (tokens.remainingCapacity() > 0) {
                    tokens.add(slot);
                    table.placeToken(id, slot);

                    synchronized (this) {
                        playerThread.interrupt();
                    }
                }
            }
        }
    }


    /**

     ** Award a point to a player and perform other related actions.
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        milsToWait=env.config.pointFreezeMillis;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }
    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        milsToWait=env.config.penaltyFreezeMillis;
    }

    public int score() {
        return score;
    }

    public Queue<Integer> cardsTokens(){return tokens;}
    public void resetTokens(){
        while (!tokens.isEmpty()){
            int slot = tokens.remove();
            tokens.remove(slot);
            table.removeToken(id, slot);
        }
    }
}
