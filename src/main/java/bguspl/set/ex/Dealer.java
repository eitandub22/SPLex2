package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
import java.util.concurrent.Semaphore;
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
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    private Semaphore requestingPlayersSemaphore = new Semaphore(1);
    private Queue<Player> requestingPlayers;

    private Thread[] playerThreads;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.terminate = false;
        this.requestingPlayers = new LinkedList<>();
        playerThreads = new Thread[players.length];
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for(Player player : players){
            ThreadLogger playerThread = new ThreadLogger(player, Integer.toString(player.id), env.logger);
            playerThread.startWithLog();
        }
        while (!shouldFinish()) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 1000;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        for(Player p : players){
            p.terminate();
        }
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
     * Checks cards should be removed (a set) from the table and removes them.
     */
    private void removeCardsFromTable() {
        try{this.requestingPlayersSemaphore.acquire();} catch (InterruptedException e){}
        while(!requestingPlayers.isEmpty()){
            Player requestingPlayer = requestingPlayers.remove();
            this.requestingPlayersSemaphore.release();

            List<Integer> playerTokens = table.getTokens(requestingPlayer.id);
            if(playerTokens.size() == 3){
                int[] playerSet = playerTokens.stream().mapToInt(i -> this.table.getCardFromSlot((i))).toArray();
                if(env.util.testSet(playerSet)){
                    for(Integer slot : playerSet){
                        table.removeCard(slot);
                        table.removeTokensFromSlot(slot);
                    }
                    updateTimerDisplay(true);
                    requestingPlayer.point();
                }
                else{
                    requestingPlayer.penalty();
                }
            }
            else{
                playerThreads[requestingPlayer.id].notifyAll();
            }
            try{this.requestingPlayersSemaphore.acquire();} catch (InterruptedException e){}
        }
        this.requestingPlayersSemaphore.release();
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        List<Integer> spots = table.getEmptySlots();
        Collections.shuffle(deck);
        while(!spots.isEmpty() && !deck.isEmpty()){
            table.placeCard(this.deck.remove(0), spots.remove(0));
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{
            synchronized (requestingPlayers){
                requestingPlayers.wait(1000);
            }
        }
        catch (InterruptedException e){}
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(reset){
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        else{
            long timeLeft = reshuffleTime - System.currentTimeMillis();
            env.ui.setCountdown(timeLeft, timeLeft <= this.env.config.turnTimeoutWarningMillis);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        int slotsNum = env.config.tableSize;
        synchronized (table) {
            for (int i = 0; i < slotsNum; i++) {
                deck.add(table.getCardFromSlot(i));
                table.removeCard(i);
                table.removeTokensFromSlot(i);
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int maxScore = -1;
        List<Integer> winners = new ArrayList<>();
        for (Player p : players){
            if(p.score() > maxScore) maxScore = p.score();
        }
        for (Player p : players){
            if(p.score() == maxScore) winners.add(p.id);
        }
        int[] winnersArr = new int[winners.size()];
        for(int i = 0; i < winners.size(); i++){
            winnersArr[i] = winners.get(i);
        }
        env.ui.announceWinner(winnersArr);
    }

    public void checkPlayerRequest(Player player){
        try{this.requestingPlayersSemaphore.acquire();} catch (InterruptedException e){}
        requestingPlayers.add(player);
        this.requestingPlayersSemaphore.release();

        synchronized (requestingPlayers){
            requestingPlayers.notifyAll();
        }
    }
}
