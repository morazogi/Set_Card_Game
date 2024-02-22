package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
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
    private final List<Integer> default12 = Arrays.asList(0,1,2,3,4,5,6,7,8,9,10,11);
    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Thread DealerThread;

    private long clock=60000;
    private long nextTimeClocker;

    private Queue<Integer> toCheck = new SynchronousQueue<>();
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        DealerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        while (!shouldFinish()) {
            placeCardsOnTable(default12);
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

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        nextTimeClocker=System.currentTimeMillis();
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
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
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * when we find a set we remove here
     */
    private void removeCardsFromTable() throws InterruptedException {
        while(!toCheck.isEmpty()) {
            Queue<Integer> tokens = players[toCheck.peek()].cardsTokens();
            if (isSet(tokens)) {//check for set
                List<Integer> RemovedCards = new ArrayList<>();
                while (!tokens.isEmpty()) {
                    int card = tokens.remove();
                    RemovedCards.add(table.cardToSlot[card]);
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
    private void placeCardsOnTable() {
        // TODO implement
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        nextTimeClocker+=+1000;
        long toUpdateTime= nextTimeClocker -System.currentTimeMillis();
        if(toUpdateTime>0) {
            try {
                this.DealerThread.wait(toUpdateTime);
            } catch (InterruptedException ignore) {}
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(System.currentTimeMillis()>=nextTimeClocker) {
            if (reset) {
                clock = env.config.turnTimeoutMillis;
                env.ui.setCountdown(clock, false);
            } else {
                clock-=1000;
                env.ui.setCountdown(clock, clock < env.config.turnTimeoutWarningMillis);
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {  // for reshuffle
        List<Integer> random = Arrays.asList(0,1,2,3,4,5,6,7,8,9,10,11);
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
