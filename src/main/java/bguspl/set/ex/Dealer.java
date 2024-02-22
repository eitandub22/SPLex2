package bguspl.set.ex;

import bguspl.set.Env;

import bguspl.set.ThreadLogger;

import java.util.Queue;
import java.util.LinkedList;
import java.util.List;
import java.util.Iterator;
import java.util.Collections;
import java.util.ArrayList;
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
    private Queue<Player> requestingPlayers;

    private Thread[] playerThreads;

    private final long SECOND = 1000;
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
            playerThreads[player.id] = playerThread;
            playerThread.startWithLog();
        }
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(true);
            removeAllCardsFromTable();
            table.removeAllTokens();
        }
        System.out.println("before join");
        announceWinners();
        for(Thread playerThread : playerThreads){
            try{
                if(playerThread != null){
                    playerThread.join();
                }
            }catch(InterruptedException e){}
        }
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
        for(Player player : players){
            player.terminate();
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
        while(!requestingPlayers.isEmpty() && !terminate){
            Player requestingPlayer = requestingPlayers.remove();
            List<Integer> playerTokens = table.getTokens(requestingPlayer.id);
            int[] playerSet = playerTokens.stream().mapToInt(i -> this.table.getCardFromSlot(i)).toArray();
            //only if the player put 3 tokens, check for removal
            if(playerSet.length == this.env.config.featureSize){
                if(env.util.testSet(playerSet)){
                    for(Integer slot : playerTokens){
                        table.removeCard(slot);
                        //this.deck.remove(this.deck.indexOf(slot));
                        List<Integer> playersInSlot = table.tokensToPlayers.get(slot);
                        table.removeTokensFromSlot(slot);
                        for(Integer playerId : playersInSlot){
                            playerThreads[playerId].notifyAll();
                        }
                    }
                    updateTimerDisplay(true);
                    requestingPlayer.point();
                }
                else{
                    requestingPlayer.penalty();
                }
            }
            else{
                if(playerThreads[requestingPlayer.id] != null) playerThreads[requestingPlayer.id].notifyAll();
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        //TODO check if there is set in deck and table before placing cards
        List<Integer> spots = table.getEmptySlots();
        Collections.shuffle(spots);
        Iterator<Integer> cards = deck.iterator();
        for(Integer spot : spots){
            if(!this.deck.isEmpty()){
                this.table.placeCard(this.deck.remove(0), spot);
            }
            else{
                break;
           }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try{
            synchronized (requestingPlayers){
                requestingPlayers.wait(1000);//sleep for a second and then update the timer or until a player wants to check a set
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
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + SECOND;
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

    public void checkPlayerRequest(Player player){
        requestingPlayers.add(player);
        synchronized (requestingPlayers){
            requestingPlayers.notifyAll();
        }
    }
}
