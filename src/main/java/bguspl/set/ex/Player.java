package bguspl.set.ex;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
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
    private Thread playerThread;

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
    private int score;
    
    /**
     * The game dealer
     */
    private Dealer dealer;

    /**
     * Queue of keys pressed.
     */
    private Queue<Integer> keyQueue;

    /**
     * Number of tokens on the table
     */
    private int tokensPlaced;
    

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
        this.keyQueue = new LinkedList<Integer>();
        this.dealer = dealer;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run(){
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();

        int currKey = 0;
        boolean removedToken = false;
        while (!terminate) {
            synchronized(keyQueue){
                while(this.keyQueue.size() == 0 && !terminate){
                    try{
                        this.keyQueue.wait();
                    }catch(InterruptedException e){}
                }
                currKey = this.keyQueue.remove();
                this.keyQueue.notifyAll();
            }
            if(!terminate) continue;

            removedToken = this.table.removeToken(this.id, currKey);
            if(!human) this.notifyAll();

            if(!removedToken && tokensPlaced < 3){
                this.table.placeToken(this.id, currKey);
                tokensPlaced++;
            }
            else if(removedToken) tokensPlaced--;

            if(tokensPlaced >= 3){
                synchronized(this.dealer){this.dealer.notifyAll();}//TODO make sure you notify dealer correctly
                try{
                    synchronized(this) {this.wait();}
                }catch(InterruptedException e){}
            }
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
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            Random rnd = new java.util.Random();
            while (!terminate) {
                while(this.keyQueue.size() >=  3 && !terminate)
                {
                    try{
                        synchronized(this) {this.wait();}
                    }catch(InterruptedException ignored){}
                }
                
                keyPressed(rnd.nextInt(this.env.config.columns * this.env.config.rows));

                try{Thread.sleep(rnd.nextInt(500) + 500);} //This is A smart ai not a fast ai
                catch(InterruptedException e){}
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
        if(!human) aiThread.interrupt();
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        synchronized(this.keyQueue){
            this.keyQueue.add(slot);
            if(this.keyQueue.size() >= 3) this.keyQueue.remove();
            this.keyQueue.notifyAll();
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);

        try{
            playerThread.sleep(this.env.config.pointFreezeMillis);
        }catch(InterruptedException egnored){}

        synchronized(this.keyQueue){
            this.keyQueue.clear(); 
            synchronized(this){this.notifyAll();}
        }
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try{
            playerThread.sleep(this.env.config.penaltyFreezeMillis);
        }catch(InterruptedException egnored){}

        synchronized(this.keyQueue){
            this.keyQueue.clear();
            synchronized(this){this.notifyAll();}
        }
    }

    public int score() {
        return score;
    }
}
