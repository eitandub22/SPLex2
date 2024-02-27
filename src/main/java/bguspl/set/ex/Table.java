package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)
    /** 
     * Mapping between a player and the tokens he has on the table.
    */
    protected final List<List<Integer>> playersToTokens;
    /**
     * Mapping between a slot and the playes that placed a token on it.
     */
    protected final List<List<Integer>> tokensToPlayers;
    /**
     * Lock for slotToCard and cardToSlot
     */
    private final Object cardsLock;
    /**
     * Lock for playersToTokens and tokensToPlayers
     */
    private final Object tokensLock;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.cardsLock = new Object();
        this.tokensLock = new Object();
        
        this.playersToTokens = new ArrayList<List<Integer>>(env.config.players);
        for(int i = 0; i < env.config.tableSize; i++){
            this.playersToTokens.add(new LinkedList<Integer>());
        }

        this.tokensToPlayers = new ArrayList<List<Integer>>(env.config.tableSize);
        for(int i = 0; i < env.config.tableSize; i++){
            this.tokensToPlayers.add(new LinkedList<Integer>());
        }
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        synchronized(cardsLock){
            List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
            env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
                StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
                List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
                int[][] features = env.util.cardsToFeatures(set);
                System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
            });
        }
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        synchronized(cardsLock){
            for (Integer card : slotToCard){
                if (card != null){
                    ++cards;
                }
            }
        }
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        synchronized(cardsLock){
            cardToSlot[card] = slot;
            slotToCard[slot] = card;
        }

        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}

        synchronized(cardsLock){
            if(slotToCard[slot] == null) return; //no card to remove
            cardToSlot[slotToCard[slot]] = null;
            slotToCard[slot] = null;
        }
        
        env.ui.removeCard(slot);
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        synchronized(cardsLock){if(this.slotToCard[slot] == null) return;}//cant put tokn on empty slot
        
        synchronized(this.tokensLock){
            this.playersToTokens.get(player).add(slot);
            this.tokensToPlayers.get(slot).add(player);
        }

        this.env.ui.placeToken(player, slot);
    }
 
    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        synchronized(this.tokensLock){
            int tokenIndex = this.playersToTokens.get(player).indexOf(slot);
            if(tokenIndex == -1 || this.tokensToPlayers.isEmpty()) return false;


            int index = this.tokensToPlayers.get(slot).indexOf(player);
            if(index == -1 || this.playersToTokens.isEmpty()) return false;
            this.playersToTokens.get(player).remove(this.playersToTokens.get(player).indexOf(slot));
            this.tokensToPlayers.get(slot).remove(this.tokensToPlayers.get(slot).indexOf(player));
        }

        this.env.ui.removeToken(player, slot);
        return true;
    }

    public List<Integer> getEmptySlots(){
        List<Integer> emptySlots = new ArrayList<>();
        synchronized(cardsLock){
            for (int i = 0; i < slotToCard.length; i++) {
                if(slotToCard[i] == null) emptySlots.add(i);
            }
        }
        return emptySlots;
    }

    public int getCardFromSlot(int slot){
        synchronized (cardsLock){
            if(slotToCard[slot] == null) return -1;
            return slotToCard[slot];
        }
    }

    public int numTokens(int player){
        synchronized(this.tokensLock){
            return this.playersToTokens.get(player).size();
        }
    }

    public List<Integer> getTokens(int playerId){
        synchronized(this.tokensLock){
            return this.playersToTokens.get(playerId).stream().collect(Collectors.toList());
        }
    }

    public void removeTokensFromSlot(int slot){
        synchronized(this.tokensLock){
            while(!this.tokensToPlayers.get(slot).isEmpty()){
                removeToken(this.tokensToPlayers.get(slot).get(0), slot);
            }
        }
    }

    public void placeCards(List<Integer> cards){
        synchronized(this.cardsLock){
            List<Integer> spots = getEmptySlots();
            Collections.shuffle(spots);
            while(!spots.isEmpty() && !cards.isEmpty()){
                placeCard(cards.remove(0), spots.remove(0));
            }
        }
    }
}
