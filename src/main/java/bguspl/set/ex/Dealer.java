package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private  List<Integer> deck;
    private List<Integer> default12 = new LinkedList<>();
    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Thread DealerThread;

    private long clock;
    private long nextTimeClocker;
    private boolean ClockChanged=true;

    private final long sec =1000;

    private BlockingQueue<Integer> toCheck ;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
       toCheck = new ArrayBlockingQueue<>(players.length);
       clock = env.config.turnTimeoutMillis;
        for (int i = 0; i < env.config.rows*env.config.columns; i++)
            default12.add(i);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        DealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        StartingPlayersThreads();
        while (!shouldFinish()) {
            placeCardsOnTable(new LinkedList<>(default12));
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    public void addCheck(int PlayerId){
        toCheck.add(PlayerId);
    }

    private synchronized void StartingPlayersThreads(){
        for(Player p:players){
            ThreadLogger PlayerThread = new ThreadLogger(p, "" +p.id, env.logger);
            PlayerThread.startWithLog();
            p.playerThread=PlayerThread;
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        nextTimeClocker=System.currentTimeMillis();
        reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis;
        nextTimeClocker=System.currentTimeMillis()+sec;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            try {
                removeCardsFromTable();
            } catch (InterruptedException ignore) {}
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * when we find a set we remove here
     */
    private void removeCardsFromTable() throws InterruptedException {
        while(!toCheck.isEmpty()) {
            Queue<Integer> tokens = players[toCheck.peek()].cardsTokens();
            int size=tokens.size();
            for (int t=0 ; t<size;t++) {
                tokens.add(table.slotToCard[tokens.remove()]);
            }
            if (isSet(tokens)) {//check for set
                List<Integer> RemovedSlots = new ArrayList<>();
                while (!tokens.isEmpty()) {
                    int card = tokens.remove();
                    RemovedSlots.add(table.cardToSlot[card]);
                    table.removeCard(card);
                    deck.remove(card);
                }
                placeCardsOnTable(RemovedCards);
                players[toCheck.peek()].playerThread(env.config.pointFreezeMillis);
                players[toCheck.remove()].playerThread.interrupt();
            }

        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable(List<Integer> toFill) {
        Collections.shuffle(toFill);
        Collections.shuffle(deck);
        int size = toFill.size();
        int i;
        for (i = 0; i < size; i++) {
            table.placeCard(deck.remove(0), toFill.remove(0));
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if(System.currentTimeMillis()>=nextTimeClocker && !ClockChanged ) {
            updateTimerDisplay(false);
            nextTimeClocker += sec;;
        }
        ClockChanged=false;
        long toUpdateTime= nextTimeClocker - System.currentTimeMillis();
        if(toUpdateTime>0) {
            synchronized (this) {
                try {
                    wait(toUpdateTime);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        for(Player p:players){
            if(p.freeze>0) {
                p.freeze -= sec;
                env.ui.setFreeze(p.id, p.freeze);
            }
        }

        if (reset) {
            clock = env.config.turnTimeoutMillis;
            env.ui.setCountdown(clock, false);
            ClockChanged=true;
        } else {
            if(System.currentTimeMillis()>=nextTimeClocker) {
                clock -= sec;
                env.ui.setCountdown(clock, clock < env.config.turnTimeoutWarningMillis);
                nextTimeClocker += sec;
                ClockChanged=true;
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {  // for reshuffle
        List<Integer> random = new LinkedList<>();
        for (int i = 0; i < env.config.columns*env.config.rows; i++)
            random.add(i);
        Collections.shuffle(random);
        players[0].resetTokens();
        players[1].resetTokens();
        for (int i = 0; i <= 11; i++) {
            deck.add(table.slotToCard[random.get(0)]);
            table.removeCard(random.remove(0));
        }

        // ------------------TODO implement
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int [] arr;
        if (players[0].score()>players[1].score())          //player 1 win
        {
            arr = new int[1];
            arr[0] = 1;
        }
        else if (players[0].score()<players[1].score()) {   // player 2 win
            arr = new int[1];
            arr[0] = 2;
        }
        else {                                              //it's a tie
            arr = new int[2];
            arr[0] = 1;
            arr[1] = 2;
        }
        env.ui.announceWinner(arr);
        //  ------- TODO implement
    }

    private boolean isSet(Queue<Integer> tokens){
        int[] cards= tokens.stream().mapToInt(i->i).toArray();
        return env.util.testSet(cards);
    }
}
