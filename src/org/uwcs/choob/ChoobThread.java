/*
 * ChoobThread.java
 *
 * Created on June 16, 2005, 7:25 PM
 */

package org.uwcs.choob;

import org.uwcs.choob.plugins.*;
import org.uwcs.choob.modules.*;
import java.sql.*;
import org.uwcs.choob.support.*;
import java.util.*;

/**
 *
 * @author  sadiq
 */
public class ChoobThread extends Thread
{
    Object waitObject;
    DbConnectionBroker dbBroker;
    Connection dbConnection;
    Modules modules;
    int threadID;
    Map pluginMap;
    
    /**
     * Holds value of property context.
     */
    private Context context;
    
    /**
     * Holds value of property busy.
     */
    private boolean busy;
    
    /**
     * Holds value of property running.
     */
    private boolean running;
    
    /** Creates a new instance of ChoobThread */
    public ChoobThread(DbConnectionBroker dbBroker, Modules modules, Map pluginMap)
    {
        waitObject = new Object();
        
        this.dbBroker = dbBroker;
        
        this.modules = modules;
        
        threadID = (int)(Math.random() * 1000);
        
        this.pluginMap = pluginMap;
    }
    
    public void run()
    {
        running = true;
        
        while( running )
        {
            try
            {
                synchronized( waitObject )
                {
                    dbConnection = dbBroker.getConnection();                    
                    
                    busy = false;
                    
                    waitObject.wait();
                    
                    System.out.println("Thread("+threadID+") handed line " + context.getText());
                    
                    busy = true;
                    
                    if( context.getText().indexOf("~") == 0 )
                    {
                        // Namespace alias code would go here
                        
                        String pluginName = context.getText().substring(context.getText().indexOf("~")+1, context.getText().indexOf("."));
                        int endPos = 0;
                        
                        if( context.getText().indexOf(" ") < 0 ) 
                        {
                            endPos = context.getText().length();
                        }
                        else
                        {
                            endPos = context.getText().indexOf(" ");
                        }
                        
                        String commandName = context.getText().substring(context.getText().indexOf(".")+1,endPos);
                        
                        System.out.println("Looking for plugin " + pluginName + " and command " + commandName);
                        
                        if( pluginMap.get(pluginName) != null ) 
                        {
                            System.out.println("Map for " + pluginName + " is not null, calling.");
                            Object tempPlugin = ((Object)pluginMap.get(pluginName));
                            
                            BeanshellPluginUtils.doCommand(tempPlugin, commandName, context, modules);
                        }
                    }
                    
                    dbBroker.freeConnection( dbConnection );
                    
                    //System.setSecurityManager( new ChoobLaxSecurityManager() );
                }
            }
            catch( Exception e )
            {
                System.out.println("Exception: " + e);
                e.printStackTrace();
            }
            finally
            {
                busy = false;
            }            
        }
    }
    
    /**
     * Getter for property context.
     * @return Value of property context.
     */
    public Context getContext()
    {
        return this.context;
    }
    
    /**
     * Setter for property context.
     * @param context New value of property context.
     */
    public void setContext(Context context)
    {
        System.out.println("Context set for thread("+threadID+")");
        this.context = context;
    }
    
    /**
     * Getter for property busy.
     * @return Value of property busy.
     */
    public boolean isBusy()
    {
        return this.busy;
    }
    
    /**
     * Getter for property running.
     * @return Value of property running.
     */
    public boolean isRunning()
    {
        return this.running;
    }
    
    /**
     * Setter for property running.
     * @param running New value of property running.
     */
    public void setRunning(boolean running)
    {
        this.running = running;
    }
    
    /**
     * Getter for property waitObject.
     * @return Value of property waitObject.
     */
    public Object getWaitObject()
    {
        return this.waitObject;
    }
    
    /**
     * Setter for property waitObject.
     * @param waitObject New value of property waitObject.
     */
    public void setWaitObject(Object waitObject)
    {
        this.waitObject = waitObject;
    }
    
}
