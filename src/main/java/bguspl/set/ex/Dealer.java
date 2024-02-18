package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
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
    private Integer requestingPlayerId;
    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        this.terminate = false;
        this.requestingPlayerId = new Integer(-1);
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
            Collections.shuffle(deck);
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
        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
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
        Player requestingPlayer = players[requestingPlayerId];
        int[] playerSet = requestingPlayer.getTokens();// TODO implement
        if(env.util.testSet(playerSet)){
            for(Integer card : playerSet){
                table.removeCard(table.cardToSlot[card]);
            }
            updateTimerDisplay(true);
            requestingPlayer.point();
            env.ui.setFreeze(requestingPlayer.id, env.config.pointFreezeMillis);
        }
        else{
            requestingPlayer.penalty();
            env.ui.setFreeze(requestingPlayer.id, env.config.penaltyFreezeMillis);
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        List<Integer> spots = table.getEmptySlots();
        Collections.shuffle(spots);
        Iterator<Integer> cards = deck.iterator();
        synchronized (table){
            for(Integer spot : spots){
                if(cards.hasNext()){
                    try {
                        Thread.sleep(env.config.tableDelayMillis);
                    } catch (InterruptedException ignored) {}
                    table.placeCard(cards.next(), spot);
                }
                else{
                    break;
                }
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{
            synchronized (requestingPlayerId){
                requestingPlayerId.wait(1000);//sleep for a second and then update the timer or until a player wants to check a set
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
            env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        int slotsNum = env.config.columns * env.config.rows;
        synchronized (table) {
            for (int i = 0; i < slotsNum; i++) {
                deck.add(table.getCardFromSlot(i));
                table.removeCard(i);
            }
            //table.removeAllTokens(); TODO implement
        }
        synchronized (env.ui){
            for (int i = 0; i < slotsNum; i++) {
                env.ui.removeCard(i);
            }
        }
        Collections.shuffle(deck);
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

    public void checkPlayerRequest(int id){
        for(Player p : players){
            if(p.id == id){
                requestingPlayerId = id;
                notify();
            }
        }
    }
}
