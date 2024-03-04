package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
        while (!shouldFinish()) {
            placeCardsOnTable(new LinkedList<>(default12));
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    public void addCheck(int PlayerId){
        if(!toCheck.contains(PlayerId))
            toCheck.offer(PlayerId);
    }

    public void unCheck(int id){
            toCheck.remove(id);
    }

    private synchronized void StartingPlayersThreads(){
        for(Player p:players){
            ThreadLogger PlayerThread = new ThreadLogger(p, "" +p.id, env.logger);
            p.playerThread=PlayerThread;
            PlayerThread.startWithLog();
        }
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        if(players[0].playerThread!=null) {
                for(Player p:players) {
                    synchronized (p) {
                    p.shuffle = false;
                    p.notifyAll();
                }
            }
        }
        else
            StartingPlayersThreads();
        nextTimeClocker=System.currentTimeMillis();
        reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis+sec;
        nextTimeClocker=System.currentTimeMillis()+sec;

        while (!shouldFinish()&& System.currentTimeMillis() < reshuffleTime) {
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
        List<Integer> unitedDeck = new LinkedList<>(deck);
        for (int i=0;i<12;i++) {
            if (table.slotToCard[i] !=null)
                unitedDeck.add(table.slotToCard[i]);
        }
        return terminate || env.util.findSets(unitedDeck, 1).isEmpty();
    }

    /**
     * Checks cards should be removed from the table and removes them.
     * when we find a set we remove here
     */
    private void removeCardsFromTable() {
        while(!toCheck.isEmpty()) {
            Player p = players[toCheck.remove()];
            Queue<Integer> tokens = p.cardsTokens();
            Queue<Integer> checkCards = new LinkedList<>();
            List<Integer> checkSlots = new LinkedList<>();
            int size=tokens.size();
            for (Integer token : tokens) {
                    int card = table.slotToCard[token];
                    checkSlots.add(token);
                    checkCards.add(card);
            }
            if (isSet(checkCards)) {//check for set
                while (!checkCards.isEmpty()) {
                    int card = checkCards.remove();
                    table.removeCard(table.cardToSlot[card]);
                }
                for(Player player:players)
                    if(player.id!=p.id)
                        player.resetSpecificTokens(p.cardsTokens());
                p.resetTokens();
                placeCardsOnTable(checkSlots);
                updateTimerDisplay(true);
                p.point();
            }
            else
                p.penalty();
            env.ui.setFreeze(p.id, p.milsToWait);
            long updateTime;
            if(clock <= env.config.turnTimeoutWarningMillis)
                updateTime=sec/100;//10 mili sec if warning
            else
                updateTime = sec;
            p.nextFreezeTimeUpdate =System.currentTimeMillis()+updateTime;
            synchronized (p) {
                p.notifyAll();
            }
        }
    }



    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable(List<Integer> toFill) {
        Collections.shuffle(toFill);
        Collections.shuffle(deck);
        if (!deck.isEmpty()){
            int sizeD = deck.size();
            int size = toFill.size();
            int i;
            for (i = 0; i < Math.min(size,sizeD); i++) {
            table.placeCard(deck.remove(0), toFill.remove(0));
            }
        }
        for (int i = 0; i < players.length; i++) {
            env.ui.setFreeze(i,0);
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if(!ClockChanged ) {
            updateTimerDisplay(false);
        }
        ClockChanged=false;
        long toUpdateTime= nextTimeClocker - System.currentTimeMillis();
        synchronized (this) {
            boolean isFreezed=false;
            for(Player p:players)
                if(p.milsToWait>0)
                    isFreezed=true;
            if(toUpdateTime>0 && toCheck.isEmpty() && !isFreezed) {
                try {
                    wait(toUpdateTime);
                } catch (InterruptedException ignore) {}
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
            long updateTime;
            if(clock <= env.config.turnTimeoutWarningMillis)
                updateTime=sec/100;//10 mili sec if warning
            else
                updateTime = sec;

        for(Player p:players){
            if(p.milsToWait >0 && System.currentTimeMillis()>=p.nextFreezeTimeUpdate) {
                int x;
                if(p.milsToWait==1010)
                    x=500;
                p.nextFreezeTimeUpdate +=updateTime;
                p.milsToWait -=updateTime;
                if(updateTime==10)
                    env.ui.setFreeze(p.id, p.milsToWait+990);
                else
                    env.ui.setFreeze(p.id, p.milsToWait);
                if(p.milsToWait==0)
                    p.playerThread.interrupt();
            }
        }

        if (reset) {
            clock = env.config.turnTimeoutMillis;
            env.ui.setCountdown(clock, false);
            ClockChanged=true;
            reshuffleTime=System.currentTimeMillis()+env.config.turnTimeoutMillis+sec;
        } else {
                if(System.currentTimeMillis()>=nextTimeClocker) {
                    clock -= updateTime;
                    if (clock >= 0)
                        env.ui.setCountdown(clock, clock < env.config.turnTimeoutWarningMillis);
                    nextTimeClocker += updateTime;
                    ClockChanged = true;
                }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {  // for reshuffle
        if (!shouldFinish()) {
            for(Player p:players) {
                p.ResetPlayer();
                synchronized (p) {
                    p.notifyAll();
                }
            }
            List<Integer> random = new LinkedList<>();
            for (int i = 0; i < env.config.columns * env.config.rows; i++)
                random.add(i);
            Collections.shuffle(random);
            for (int i = 0; i <= 11; i++) {
                if(table.slotToCard[random.get(0)] != null)
                {
                    deck.add(table.slotToCard[random.get(0)]);
                    table.removeCard(random.remove(0));
                }
                else
                    random.remove(0);
            }
            updateTimerDisplay(true);
            toCheck.clear();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int [] arr;
        int maxPoints = 0;
        int winnersNum = 0;
        List<Integer> winners = new LinkedList<>();
        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > maxPoints){
                winnersNum=1;
                winners = new LinkedList<>();
                winners.add(i);
                maxPoints = players[i].score();
            }
            else if (players[i].score() == maxPoints){
                winnersNum++;
                winners.add(i);
            }
        }
        arr = new int[winnersNum];
        int i = 0;
        for (Integer x :winners){
            arr[i] = x;
            i++;
        }
        env.ui.announceWinner(arr);
    }

    private boolean isSet(Queue<Integer> tokens){
        int[] cards= tokens.stream().mapToInt(i->i).toArray();
        return env.util.testSet(cards);
    }
}
