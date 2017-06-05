import minichess.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.*;


/**
 * Created by ben on 5/14/2017.
 */


public class IterativePlayer extends Player {
    long remaining;
    long turnTime;
    PlayerThread running;
    boolean ready;
    TTable table;

    //best guess at branching factor, used to wait out iterations that may be almost complete
    //the more accurate the better, but favoring low values will front load our time
    //front loading time is probably best because better moves early lead to stronger positions later.
    public static final int BRANCHING_FACTOR = 10;

    public IterativePlayer(Board board, boolean isWhite) {
        super(board, isWhite);
        remaining = 297000000000L;//4:57 use 95% of available time leaves a good buffer
        ready = false;
        table = board.getTable();

        //start thinking immediately (mostly for if we're black)
        running = new PlayerThread(new Board(board.toString(), board.getTable()), isWhite, this);
        running.start();
    }

    @Override
    public synchronized Move getPlay() {
        long startTime = System.nanoTime();
        ready = false;
        turnTime = remaining / ((82 - board.getPly()) / 2);//remaining time / number of moves to make

        //stop previous thread
        running.interrupt();
        //start new thread to calculate move
        running = new PlayerThread(new Board(board.toString(), board.getTable()), isWhite, this);
        running.start();

        //first we wait for some value to be available at all
        while (!ready){
            try {
                wait(100);//this is not guaranteed to start before the other thread signaled ready, need short timeout
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        //then delay for the move time determined above
        try {
            if(HowDoTheHorsiesMove.buildOpen) wait();//ignore; extra for easily building opening table
            wait(turnTime / 1000000, ((int) (turnTime % 1000000)));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        //if the current step is probably almost finished, let it run about how long we expect,
        // if this isn't enough we need to give up
        if(System.nanoTime() - running.itrStart > running.predictedNext * .7) {//if elapsed > 70% of predicted for iteration
            long extra = running.predictedNext / 2000000;//50% of predicted as milliseconds. note: could be 0

            running.requestNotify = true;//this is a bit of a race, but if we have rally bad luck the timeout means it's fine

            //wait for either the timer to expire or the iteration to complete
            try {
                wait(extra + 1);//add 1 to ensure not 0 which is infinite wait.
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        //read the results of the last complete iteration
        Move ret = running.getMove();

        //restart the thread to reflect that the active player has chosen a move.
        running.interrupt();
        running = new PlayerThread(new Board(board.toString(), board.getTable()), isWhite, this);
        running.start();

        //deduct time spent from our timer, we use less than 5 minutes total to account for other overhead
        //done immediately before return for most accurate value
        remaining -= System.nanoTime() - startTime;
        return ret;
    }

    synchronized void gotMove() {
        ready = true;
        notify();
    }

    public void terminate() {
        if(running != null)
            running.interrupt();
    }
}

class PlayerThread extends Thread {
    Board board;
    Move move;
    boolean isWhite;
    boolean running;
    ArrayList<Move> moves;
    long itrStart;
    long itrEnd;
    long predictedNext;
    IterativePlayer p;
    boolean requestNotify;
    boolean read;

    public PlayerThread(Board board, boolean isWhite, IterativePlayer player) {
        this.board = board;
        this.isWhite = isWhite;
        running = true;
        p = player;
        requestNotify = true;
        read = false;
    }

    @Override
    public void run() {
        moves = new ArrayList<>(30);//30 is probably about optimal
        PlayerPieces pieces;
        if(isWhite)
            pieces = board.whitePieces;
        else
            pieces = board.blackPieces;
        for (Piece p : pieces) {
            p.addMovesToList(moves);
        }
        //Collections.sort(moves);//we skip this sort since we recycle the move list anyway, the deepening does it
        for (int i = 0; i < 80; i++) {
            itrStart = System.nanoTime();
            try {
                itrEvaluate(moves, i, -10000000, 10000000);
            } catch (InterruptedException e) {
                return;
            }
            itrEnd = System.nanoTime();
            predictedNext = (itrEnd - itrStart) * IterativePlayer.BRANCHING_FACTOR;//best guess branching factor
            Collections.sort(moves);
            move = moves.get(0);
            if(requestNotify) {
                p.gotMove();
                requestNotify = false;
            }

            //extra junk to build an initialized ttable to speed ou opening
            if(HowDoTheHorsiesMove.buildOpen) {
                try {
                    FileOutputStream fout = new FileOutputStream("TTable.ser");
                    ObjectOutputStream oos = new ObjectOutputStream(fout);
                    oos.writeObject(board.getTable());
                } catch (IOException e) {
                    System.err.println("error saving TTable");
                    e.printStackTrace();
                }
            }
            System.out.println(i);
        }
        //we wait if we've exited the loop in case the player hasn't retrieved it yet.
        while(!read){
            try {
                p.gotMove();
                wait(1000);
            } catch (Exception e) {
                return;
            }
        }
    }

    public Move getMove() {
        read = true;
        return move;
    }

    private int itrEvaluate(List<Move> moves, int depth, int alpha, int beta) throws InterruptedException {
        if (Thread.interrupted())//done with this thread, throw it out!
            throw new InterruptedException();
        if (depth <= 0)
            return board.getValue(); // called from a leaf, just use heuristic valuation of board

        int alphaOrig = alpha;
        TTable ttable = board.getTable();
        TTableEntry entry = null;
        boolean newEntry = false;

        //get info from ttable
        if (ttable != null) {
            entry = ttable.get(board.zobLow(), board.zobHigh());
            if (entry != null) {
                if (entry.getDepth() >= depth) {
                    if (entry.getFlag() == 0)//exact
                        return entry.getValue();
                    else if (entry.getFlag() < 0)//lower bound
                        alpha = Math.max(alpha, entry.getValue());
                    else if (entry.getFlag() > 0)//upper bound
                        beta = Math.min(beta, entry.getValue());
                    if (alpha >= beta)
                        return entry.getValue();
                }
            } else {
                newEntry = true;
                entry = new TTableEntry(board.zobHigh(), depth);
            }
        }

        //do the search
        PlayerPieces pieces;
        if (!board.isWhiteTurn()) //grab opposite pieces to get moves after move. this will be accurate then too
            pieces = board.whitePieces;
        else
            pieces = board.blackPieces;
        int value = Integer.MIN_VALUE;
        /*
        for (Move move : moves) {
        /*/
        int s = moves.size();
        int deepsize = s;
        for (int i = 0; i < s; i++) {
            Move move = moves.get(i);
        //*/
            if(move.getValue() > 9000000) {
                value = 100000 * depth; //return early if taking a king, we found a win, add depth to favor faster wins
                break;
            }
            //make the move to analyze the board that results
            move.make(board);
            //create list of possible moves available to opponent
            //30 is big enough >99.9% of the time, and not having to grow the array makes a big difference
            List<Move> moves2 = new ArrayList<>(30);
            for (Piece p : pieces) {
                p.addMovesToList(moves2);
            }
            deepsize += moves2.size();//save the size of the top few tiers of current subtree for cheap size estimate

            //look deeper for moves that involve capturing pieces
            int active = 1;// (depth <= 1 && move.wasCapture()) ? 0 : 1;//0 if a piece was captured by the last move

            //recur on the evaluator to value this move
            int moveVal = -itrEvaluate(moves2, depth - active, -beta, -alpha);
            moves2 = null;
            move.setValue(moveVal);
            //set the value to the highest seen in this branch
            value = Math.max(moveVal, value);
            //set the alpha to the highest seen overall (that wasn't pruned by early return above)
            alpha = Math.max(alpha, moveVal);
            //undo the move now that we've evaluated it
            move.undo(board);
            //if the move is better than the best in another branch, we assume we won't be allowed to reach this branch
            if(moveVal >= beta) {
                break;
            }
        }
        moves = null;

        //store our info in the ttable
        if(ttable != null) {
            entry.setSize(deepsize);//TODO deepsize may be overly granular, keeping old entries too long
            entry.setValue(value);
            if (value <= alphaOrig)
                entry.setFlag(1);
            else if (value >= beta)
                entry.setFlag(-1);
            else
                entry.setFlag(0);
            entry.setDepth(Math.max(depth, entry.getDepth()));
            //if(entry.getFlag() == 0)
                if (newEntry)
                    ttable.set(board.zobLow(), entry);
            //else
                //ttable.remove(board.zobLow());
        }

        return value;
    }
}