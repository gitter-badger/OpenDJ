/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyright [year] [name of copyright owner]".
 *
 * Copyright 2008-2009 Sun Microsystems, Inc.
 * Portions Copyright 2011-2016 ForgeRock AS.
 */
package org.opends.guitools.controlpanel.task;

import static com.forgerock.opendj.cli.Utils.*;
import static com.forgerock.opendj.util.OperatingSystem.*;

import static org.opends.messages.AdminToolMessages.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.forgerock.i18n.LocalizableMessage;
import org.forgerock.opendj.ldap.Attribute;
import org.forgerock.opendj.ldap.AttributeDescription;
import org.forgerock.opendj.ldap.ByteString;
import org.forgerock.opendj.ldap.DN;
import org.forgerock.opendj.ldap.Modification;
import org.opends.admin.ads.util.ConnectionWrapper;
import org.opends.guitools.controlpanel.datamodel.ControlPanelInfo;
import org.opends.guitools.controlpanel.datamodel.ServerDescriptor;
import org.opends.guitools.controlpanel.event.ConfigurationElementCreatedEvent;
import org.opends.guitools.controlpanel.event.ConfigurationElementCreatedListener;
import org.opends.guitools.controlpanel.event.PrintStreamListener;
import org.opends.guitools.controlpanel.ui.ColorAndFontConstants;
import org.opends.guitools.controlpanel.ui.ProgressDialog;
import org.opends.guitools.controlpanel.util.ApplicationPrintStream;
import org.opends.guitools.controlpanel.util.ConfigReader;
import org.opends.guitools.controlpanel.util.ProcessReader;
import org.opends.guitools.controlpanel.util.Utilities;
import org.opends.quicksetup.Installation;
import org.opends.quicksetup.UserData;
import org.opends.server.core.DirectoryServer;
import org.opends.server.types.DirectoryException;
import org.opends.server.types.HostPort;
import org.opends.server.types.InitializationException;
import org.forgerock.opendj.ldap.schema.Schema;
import org.opends.server.util.SetupUtils;

import com.forgerock.opendj.cli.CommandBuilder;

/**
 * The class used to define a number of common methods and mechanisms for the
 * tasks that are run in the Control Panel.
 */
public abstract class Task
{
  private static final String localHostName = UserData.getDefaultHostName();
  private static final int MAX_BINARY_LENGTH_TO_DISPLAY = 1024;

  /** The different task types. */
  public enum Type
  {
    /** New Base DN creation. */
    NEW_BASEDN,
    /** New index creation. */
    NEW_INDEX,
    /** Modification of indexes. */
    MODIFY_INDEX,
    /** Deletion of indexes. */
    DELETE_INDEX,
    /** Creation of VLV indexes. */
    NEW_VLV_INDEX,
    /** Modification of VLV indexes. */
    MODIFY_VLV_INDEX,
    /** Deletion of VLV indexes. */
    DELETE_VLV_INDEX,
    /** Import of an LDIF file. */
    IMPORT_LDIF,
    /** Export of an LDIF file. */
    EXPORT_LDIF,
    /** Backup. */
    BACKUP,
    /** Restore. */
    RESTORE,
    /** Verification of indexes. */
    VERIFY_INDEXES,
    /** Rebuild of indexes. */
    REBUILD_INDEXES,
    /** Enabling of Windows Service. */
    ENABLE_WINDOWS_SERVICE,
    /** Disabling of Windows Service. */
    DISABLE_WINDOWS_SERVICE,
    /** Starting the server. */
    START_SERVER,
    /** Stopping the server. */
    STOP_SERVER,
    /** Updating the java settings for the different command-lines. */
    JAVA_SETTINGS_UPDATE,
    /** Creating a new element in the schema. */
    NEW_SCHEMA_ELEMENT,
    /** Deleting an schema element. */
    DELETE_SCHEMA_ELEMENT,
    /** Modify an schema element. */
    MODIFY_SCHEMA_ELEMENT,
    /** Modifying an entry. */
    MODIFY_ENTRY,
    /** Creating an entry. */
    NEW_ENTRY,
    /** Deleting an entry. */
    DELETE_ENTRY,
    /** Deleting a base DN. */
    DELETE_BASEDN,
    /** Deleting a backend. */
    DELETE_BACKEND,
    /** Other task. */
    OTHER
  }

  /** The state on which the task can be. */
  public enum State
  {
    /** The task is not started. */
    NOT_STARTED,
    /** The task is running. */
    RUNNING,
    /** The task finished successfully. */
    FINISHED_SUCCESSFULLY,
    /** The task finished with error. */
    FINISHED_WITH_ERROR
  }

  /**
   * Returns the names of the backends that are affected by the task.
   * @return the names of the backends that are affected by the task.
   */
  public abstract Set<String> getBackends();

  /** The current state of the task. */
  protected State state = State.NOT_STARTED;
  /** The return code of the task. */
  protected Integer returnCode;
  /** The last exception encountered during the task execution. */
  protected Throwable lastException;
  /**
   * The progress logs of the task.  Note that the user of StringBuffer is not
   * a bug, because of the way the contents of logs is updated, using
   * StringBuffer instead of StringBuilder is required.
   */
  private final StringBuffer logs = new StringBuffer();
  /** The error logs of the task. */
  private final StringBuilder errorLogs = new StringBuilder();
  /** The standard output logs of the task. */
  private final StringBuilder outputLogs = new StringBuilder();
  /** The print stream for the error logs. */
  protected final ApplicationPrintStream errorPrintStream = new ApplicationPrintStream();
  /** The print stream for the standard output logs. */
  protected final ApplicationPrintStream outPrintStream = new ApplicationPrintStream();

  /**
   * The process (if any) that the task launched.  For instance if this is a
   * start server task, the process generated executing the start-ds
   * command-line.
   */
  private Process process;
  private final ControlPanelInfo info;
  private final ServerDescriptor server;
  private String binDir;
  private final ProgressDialog progressDialog;
  private final List<ConfigurationElementCreatedListener> confListeners = new ArrayList<>();

  /**
   * Constructor of the task.
   * @param info the control panel information.
   * @param progressDialog the progress dialog where the task progress will be
   * displayed.
   */
  protected Task(ControlPanelInfo info, ProgressDialog progressDialog)
  {
    this.info = info;
    this.progressDialog = progressDialog;
    outPrintStream.addListener(new PrintStreamListener()
    {
      /**
       * Add a new line to the logs.
       * @param msg the new line.
       */
      @Override
      public void newLine(String msg)
      {
        outputLogs.append(msg).append("\n");
        logs.append(msg).append("\n");
      }
    });
    errorPrintStream.addListener(new PrintStreamListener()
    {
      /**
       * Add a new line to the error logs.
       * @param msg the new line.
       */
      @Override
      public void newLine(String msg)
      {
        errorLogs.append(msg).append("\n");
        logs.append(msg).append("\n");
      }
    });
    server = info.getServerDescriptor();
  }

  /**
   * Returns the ControlPanelInfo object.
   * @return the ControlPanelInfo object.
   */
  public ControlPanelInfo getInfo()
  {
    return info;
  }

  /**
   * Stops the pooling and initializes the configuration.
   *
   * @throws DirectoryException
   *           if the configuration cannot be deregistered
   * @throws InitializationException
   *           if a problem occurs during configuration initialization
   */
  protected void stopPoolingAndInitializeConfiguration() throws DirectoryException, InitializationException
  {
    getInfo().stopPooling();
    if (getInfo().mustDeregisterConfig())
    {
      DirectoryServer.getInstance().getServerContext().getBackendConfigManager()
        .deregisterBaseDN(DN.valueOf("cn=config"));
    }
    DirectoryServer.getInstance().initializeConfiguration(ConfigReader.configFile);
    getInfo().setMustDeregisterConfig(true);
  }

  /**
   * Initializes the configuration and starts the pooling.
   *
   * @throws InitializationException
   *           if a problem occurs during configuration initialization
   */
  protected void startPoolingAndInitializeConfiguration() throws InitializationException
  {
    DirectoryServer.getInstance().initializeConfiguration(ConfigReader.configFile);
    getInfo().startPooling();
  }

  /**
   * Returns the logs of the task.
   * @return the logs of the task.
   */
  public String getLogs()
  {
    return logs.toString();
  }

  /**
   * Returns the error logs of the task.
   * @return the error logs of the task.
   */
  public String getErrorLogs()
  {
    return errorLogs.toString();
  }

  /**
   * Returns the output logs of the task.
   * @return the output logs of the task.
   */
  public String getOutputLogs()
  {
    return outputLogs.toString();
  }

  /**
   * Returns the state of the task.
   * @return the state of the task.
   */
  public State getState()
  {
    return state;
  }

  /**
   * Returns last exception encountered during the task execution.
   * Returns <CODE>null</CODE> if no exception was found.
   * @return last exception encountered during the task execution.
   */
  public Throwable getLastException()
  {
    return lastException;
  }

  /**
   * Returns the return code (this makes sense when the task launches a
   * command-line, it will return the error code returned by the command-line).
   * @return the return code.
   */
  public Integer getReturnCode()
  {
    return returnCode;
  }

  /**
   * Returns the process that the task launched.
   * Returns <CODE>null</CODE> if not process was launched.
   * @return the process that the task launched.
   */
  public Process getProcess()
  {
    return process;
  }

  /**
   * Returns the progress dialog.
   * @return the progress dialog.
   */
  protected ProgressDialog getProgressDialog()
  {
    return progressDialog;
  }

  /**
   * Tells whether a new server descriptor should be regenerated when the task
   * is over.  If the task has an influence in the configuration or state of
   * the server (for instance the creation of a base DN) this method should
   * return <CODE>true</CODE> so that the configuration will be re-read and
   * all the ConfigChangeListeners will receive a notification with the new
   * configuration.
   * @return <CODE>true</CODE> if a new server descriptor must be regenerated
   * when the task is over and <CODE>false</CODE> otherwise.
   */
  public boolean regenerateDescriptor()
  {
    return true;
  }

  /**
   * Method that is called when everything is finished after updating the
   * progress dialog.  It is called from the event thread.
   */
  public void postOperation()
  {
    // no-op
  }

  /**
   * The description of the task.  It is used in both the incompatibility
   * messages and in the warning message displayed when the user wants to
   * quit and there are tasks running.
   * @return the description of the task.
   */
  public abstract LocalizableMessage getTaskDescription();

  /**
   * Adds a configuration element created listener.
   * @param listener the listener.
   */
  public void addConfigurationElementCreatedListener(
      ConfigurationElementCreatedListener listener)
  {
    confListeners.add(listener);
  }

  /**
   * Removes a configuration element created listener.
   * @param listener the listener.
   */
  public void removeConfigurationElementCreatedListener(
      ConfigurationElementCreatedListener listener)
  {
    confListeners.remove(listener);
  }

  /**
   * Notifies the configuration element created listener that a new object has
   * been created.
   * @param configObject the created object.
   */
  protected void notifyConfigurationElementCreated(Object configObject)
  {
    for (ConfigurationElementCreatedListener listener : confListeners)
    {
      listener.elementCreated(
          new ConfigurationElementCreatedEvent(this, configObject));
    }
  }

  /**
   * Returns a String representation of a value.  In general this is called
   * to display the command-line equivalent when we do a modification in an
   * entry.  But since some attributes must be obfuscated (like the user
   * password) we pass through this method.
   * @param attrDesc the attribute description.
   * @param value the attribute value.
   * @return the obfuscated String representing the attribute value to be
   * displayed in the logs of the user.
   */
  private String obfuscateAttributeStringValue(AttributeDescription attrDesc, ByteString value)
  {
    if (Utilities.mustObfuscate(attrDesc.toString(),
        getInfo().getServerDescriptor().getSchema()))
    {
      return OBFUSCATED_VALUE;
    }
    else if (displayBase64(attrDesc.toString()))
    {
      if (value.length() > MAX_BINARY_LENGTH_TO_DISPLAY)
      {
        return INFO_CTRL_PANEL_VALUE_IN_BASE64.get().toString();
      }
      return value.toBase64String();
    }
    else
    {
      return value.toString();
    }
  }

  /**
   * Obfuscates (if required) the attribute value in an LDIF line.
   * @param line the line of the LDIF file that must be treated.
   * @return the line obfuscated.
   */
  protected String obfuscateLDIFLine(String line)
  {
    int index = line.indexOf(":");
    if (index != -1)
    {
      String attrName = line.substring(0, index).trim();
      if (Utilities.mustObfuscate(attrName,
          getInfo().getServerDescriptor().getSchema()))
      {
        return attrName + ": " + OBFUSCATED_VALUE;
      }
    }
    return line;
  }

  /**
   * Executes a command-line synchronously.
   * @param commandLineName the command line full path.
   * @param args the arguments for the command-line.
   * @return the error code returned by the command-line.
   */
  protected int executeCommandLine(String commandLineName, String[] args)
  {
    returnCode = -1;
    String[] cmd = new String[args.length + 1];
    cmd[0] = commandLineName;
    System.arraycopy(args, 0, cmd, 1, args.length);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    // Use the java args in the script.
    Map<String, String> env = pb.environment();
    //env.put(SetupUtils.OPENDJ_JAVA_ARGS, "");
    env.remove(SetupUtils.OPENDJ_JAVA_ARGS);
    env.remove("CLASSPATH");
    ProcessReader outReader = null;
    ProcessReader errReader = null;
    try {
      process = pb.start();

      outReader = new ProcessReader(process, outPrintStream, false);
      errReader = new ProcessReader(process, errorPrintStream, true);

      outReader.startReading();
      errReader.startReading();

      returnCode = process.waitFor();
    } catch (Throwable t)
    {
      lastException = t;
    }
    finally
    {
      if (outReader != null)
      {
        outReader.interrupt();
      }
      if (errReader != null)
      {
        errReader.interrupt();
      }
    }
    return returnCode;
  }

  /**
   * Informs of whether the task to be launched can be launched or not. Every
   * task must implement this method so that we avoid launching in paralel two
   * tasks that are not compatible.  Note that in general if the current task
   * is not running this method will return <CODE>true</CODE>.
   *
   * @param taskToBeLaunched the Task that we are trying to launch.
   * @param incompatibilityReasons the list of incompatibility reasons that
   * must be updated.
   * @return <CODE>true</CODE> if the task that we are trying to launch can be
   * launched in parallel with this task and <CODE>false</CODE> otherwise.
   */
  public abstract boolean canLaunch(Task taskToBeLaunched,
      Collection<LocalizableMessage> incompatibilityReasons);

  /** Execute the task. This method is synchronous. */
  public abstract void runTask();

  /**
   * Returns the type of the task.
   * @return the type of the task.
   */
  public abstract Type getType();

  /**
   * Returns the binary/script directory.
   * @return the binary/script directory.
   */
  private String getBinaryDir()
  {
    if (binDir == null)
    {
      File f = Installation.getLocal().getBinariesDirectory();
      try
      {
        binDir = f.getCanonicalPath();
      }
      catch (Throwable t)
      {
        binDir = f.getAbsolutePath();
      }
      if (binDir.lastIndexOf(File.separatorChar) != binDir.length() - 1)
      {
        binDir += File.separatorChar;
      }
    }

    return binDir;
  }

  /**
   * Check whether the provided task and this task run on the same server.
   * @param task the task the task to be analyzed.
   * @return <CODE>true</CODE> if both tasks run on the same server and
   * <CODE>false</CODE> otherwise.
   */
  protected boolean runningOnSameServer(Task task)
  {
    if (getServer().isLocal() && task.getServer().isLocal())
    {
      return true;
    }

    // Compare the host name and the instance path. This is safer than
    // comparing ports: we might be running locally on a stopped instance with
    // the same configuration as a "remote" (though located on the same machine) server.
    String host1 = getServer().getHostname();
    String host2 = task.getServer().getHostname();
    boolean runningOnSameServer = host1 == null ? host2 == null : host1.equalsIgnoreCase(host2);
    if (runningOnSameServer)
    {
      String f1 = getServer().getInstancePath();
      String f2 = task.getServer().getInstancePath();
      return Objects.equals(f1, f2);
    }
    return runningOnSameServer;
  }

  /**
   * Returns the server descriptor on which the task was launched.
   * @return the server descriptor on which the task was launched.
   */
  public ServerDescriptor getServer()
  {
    return server;
  }

  /**
   * Returns the full path of the command-line associated with this task or
   * <CODE>null</CODE> if there is not a command-line (or a single command-line)
   * associated with the task.
   * @return the full path of the command-line associated with this task.
   */
  protected abstract String getCommandLinePath();

  /**
   * Returns the full path of the command-line for a given script name.
   * @param scriptBasicName the script basic name (with no extension).
   * @return the full path of the command-line for a given script name.
   */
  protected String getCommandLinePath(String scriptBasicName)
  {
    if (isWindows())
    {
      return getBinaryDir() + scriptBasicName + ".bat";
    }
    return getBinaryDir() + scriptBasicName;
  }

  /**
   * Returns the list of command-line arguments.
   * @return the list of command-line arguments.
   */
  protected abstract List<String> getCommandLineArguments();

  /**
   * Returns the list of obfuscated command-line arguments.  This is called
   * basically to display the equivalent command-line to the user.
   * @param clearArgs the arguments in clear.
   * @return the list of obfuscated command-line arguments.
   */
  protected List<String> getObfuscatedCommandLineArguments(List<String> clearArgs)
  {
    String[] toObfuscate = { "--bindPassword", "--currentPassword", "--newPassword" };
    ArrayList<String> args = new ArrayList<>(clearArgs);
    for (int i=1; i<args.size(); i++)
    {
      for (String argName : toObfuscate)
      {
        if (args.get(i-1).equalsIgnoreCase(argName))
        {
          args.set(i, OBFUSCATED_VALUE);
          break;
        }
      }
    }
    return args;
  }

  /**
   * Returns the command-line arguments that correspond to the configuration.
   * This method is called to remove them when we display the equivalent
   * command-line.  In some cases we run the methods of the command-line
   * directly (on this JVM) instead of launching the script in another process.
   * When we call this methods we must add these arguments, but they are not
   * to be included as arguments of the command-line (when is launched as a
   * script).
   * @return the command-line arguments that correspond to the configuration.
   */
  protected List<String> getConfigCommandLineArguments()
  {
    return Arrays.asList("--configFile", ConfigReader.configFile);
  }

  /**
   * Returns the list of arguments related to the connection (host, port, bind
   * DN, etc.).
   * @return the list of arguments related to the connection.
   */
  protected List<String> getConnectionCommandLineArguments()
  {
    return getConnectionCommandLineArguments(true, false);
  }

  /**
   * Returns the list of arguments related to the connection (host, port, bind
   * DN, etc.).
   * @param useAdminConnector use the administration connector to generate
   * the command line.
   * @param addConnectionTypeParameters add the connection type parameters
   * (--useSSL or --useStartTLS parameters: for ldapadd, ldapdelete, etc.).
   * @return the list of arguments related to the connection.
   */
  protected List<String> getConnectionCommandLineArguments(
      boolean useAdminConnector, boolean addConnectionTypeParameters)
  {
    ConnectionWrapper conn = useAdminConnector
        ? getInfo().getConnection()
        : getInfo().getUserDataConnection();

    List<String> args = new ArrayList<>();
    if (isServerRunning() && conn != null)
    {
      HostPort hostPort = conn.getHostPort();
      String hostName = localHostName;
      if (hostName == null || !getInfo().getServerDescriptor().isLocal())
      {
        hostName = hostPort.getHost();
      }
      boolean isLdaps = conn.isLdaps();
      boolean isStartTls = conn.isStartTls();
      String bindDN = conn.getBindDn().toString();
      String bindPwd = conn.getBindPassword();
      args.add("--hostName");
      args.add(hostName);
      args.add("--port");
      args.add(String.valueOf(hostPort.getPort()));
      args.add("--bindDN");
      args.add(bindDN);
      args.add("--bindPassword");
      args.add(bindPwd);
      if (isLdaps || isStartTls)
      {
        args.add("--trustAll");
      }
      if (isLdaps && addConnectionTypeParameters)
      {
        args.add("--useSSL");
      }
      else if (isStartTls && addConnectionTypeParameters)
      {
        args.add("--useStartTLS");
      }
    }
    return args;
  }

  /**
   * Returns the noPropertiesFile argument.
   * @return the noPropertiesFile argument.
   */
  protected String getNoPropertiesFileArgument()
  {
    return "--noPropertiesFile";
  }

  /**
   * Returns the command-line to be displayed (when we display the equivalent
   * command-line).
   * @return the command-line to be displayed.
   */
  public String getCommandLineToDisplay()
  {
    String cmdLineName = getCommandLinePath();
    if (cmdLineName != null)
    {
      List<String> args =
        getObfuscatedCommandLineArguments(getCommandLineArguments());
      args.removeAll(getConfigCommandLineArguments());
      return getEquivalentCommandLine(cmdLineName, args);
    }
    return null;
  }

  /**
   * Commodity method to know if the server is running or not.
   * @return <CODE>true</CODE> if the server is running and <CODE>false</CODE>
   * otherwise.
   */
  protected boolean isServerRunning()
  {
    return getInfo().getServerDescriptor().getStatus() ==
      ServerDescriptor.ServerStatus.STARTED;
  }

  /**
   * Returns the print stream for the error logs.
   * @return the print stream for the error logs.
   */
  public ApplicationPrintStream getErrorPrintStream()
  {
    return errorPrintStream;
  }

  /**
  * Returns the print stream for the output logs.
  * @return the print stream for the output logs.
  */
  public ApplicationPrintStream getOutPrintStream()
  {
    return outPrintStream;
  }

  /**
   * Prints the equivalent modify command line in the progress dialog.
   * @param dn the dn of the modified entry.
   * @param mods the modifications.
   * @param useAdminCtx use the administration connector.
   */
  protected void printEquivalentCommandToModify(DN dn, Collection<Modification> mods, boolean useAdminCtx)
  {
    ArrayList<String> args = new ArrayList<>(getObfuscatedCommandLineArguments(
        getConnectionCommandLineArguments(useAdminCtx, true)));
    args.add(getNoPropertiesFileArgument());
    String equiv = getEquivalentCommandLine(getCommandLinePath("ldapmodify"), args);

    StringBuilder sb = new StringBuilder();
    sb.append(INFO_CTRL_PANEL_EQUIVALENT_CMD_TO_MODIFY.get()).append("<br><b>");
    sb.append(equiv);
    sb.append("<br>");
    sb.append("dn: ").append(dn);
    boolean firstChangeType = true;
    for (Modification mod : mods)
    {
      if (firstChangeType)
      {
        sb.append("<br>changetype: modify<br>");
      }
      else
      {
        sb.append("-<br>");
      }
      firstChangeType = false;

      Attribute attr = mod.getAttribute();
      AttributeDescription attrDesc = attr.getAttributeDescription();
      switch (mod.getModificationType().asEnum())
      {
      case ADD:
        sb.append("add: ").append(attrDesc).append("<br>");
        break;
      case REPLACE:
        sb.append("replace: ").append(attrDesc).append("<br>");
        break;
      case DELETE:
        sb.append("delete: ").append(attrDesc).append("<br>");
        break;
      }

      for (ByteString value : attr)
      {
        // We are systematically adding the values in binary mode.
        // Use the attribute names to figure out the value to be displayed.
        if (displayBase64(attrDesc.toString()))
        {
          sb.append(attrDesc).append(":: ");
        }
        else
        {
          sb.append(attrDesc).append(": ");
        }
        sb.append(obfuscateAttributeStringValue(attrDesc, value));
        sb.append("<br>");
      }
    }
    sb.append("</b><br><br>");

    getProgressDialog().appendProgressHtml(Utilities.applyFont(
        sb.toString(), ColorAndFontConstants.progressFont));
  }

  /** The separator used to link the lines of the resulting command-lines. */
  private static final String LINE_SEPARATOR = CommandBuilder.HTML_LINE_SEPARATOR;

  /**
   * Returns the equivalent command line in HTML without font properties.
   * @param cmdName the command name.
   * @param args the arguments for the command line.
   * @return the equivalent command-line in HTML.
   */
  public static String getEquivalentCommandLine(String cmdName,
      List<String> args)
  {
    StringBuilder sb = new StringBuilder(cmdName);
    for (String arg : args)
    {
      if (arg.charAt(0) == '-')
      {
        sb.append(LINE_SEPARATOR);
      }
      else
      {
        sb.append(" ");
      }
      sb.append(CommandBuilder.escapeValue(arg));
    }
    return sb.toString();
  }

  /**
   * Prints the equivalent command line.
   * @param cmdName the command name.
   * @param args the arguments for the command line.
   * @param msg the message associated with the command line.
   */
  protected void printEquivalentCommandLine(String cmdName, List<String> args,
      LocalizableMessage msg)
  {
    getProgressDialog().appendProgressHtml(Utilities.applyFont(msg+"<br><b>"+
        getEquivalentCommandLine(cmdName, args)+"</b><br><br>",
        ColorAndFontConstants.progressFont));
  }

  /**
   * Tells whether the provided attribute's values must be displayed using
   * base 64 when displaying the equivalent command-line or not.
   * @param attrName the attribute name.
   * @return {@code true} if the attribute must be displayed using base 64,
   * {@code false} otherwise.
   */
  private boolean displayBase64(String attrName)
  {
    Schema schema = null;
    if (getInfo() != null)
    {
      schema = getInfo().getServerDescriptor().getSchema();
    }
    return Utilities.hasBinarySyntax(attrName, schema);
  }

  /**
   * Prints the equivalent rename command line in the progress dialog.
   * @param oldDN the old DN of the entry.
   * @param newDN the new DN of the entry.
   * @param useAdminCtx use the administration connector.
   */
  protected void printEquivalentRenameCommand(DN oldDN, DN newDN,
      boolean useAdminCtx)
  {
    ArrayList<String> args = new ArrayList<>(getObfuscatedCommandLineArguments(
        getConnectionCommandLineArguments(useAdminCtx, true)));
    args.add(getNoPropertiesFileArgument());
    String equiv = getEquivalentCommandLine(getCommandLinePath("ldapmodify"), args);
    StringBuilder sb = new StringBuilder();
    sb.append(INFO_CTRL_PANEL_EQUIVALENT_CMD_TO_RENAME.get()).append("<br><b>");
    sb.append(equiv);
    sb.append("<br>");
    sb.append("dn: ").append(oldDN);
    sb.append("<br>");
    sb.append("changetype: moddn<br>");
    sb.append("newrdn: ").append(newDN.rdn()).append("<br>");
    sb.append("deleteoldrdn: 1");
    sb.append("</b><br><br>");
    getProgressDialog().appendProgressHtml(
        Utilities.applyFont(sb.toString(),
        ColorAndFontConstants.progressFont));
  }

  /**
   * Returns the incompatible message between two tasks.
   * @param taskRunning the task that is running.
   * @param taskToBeLaunched the task that we are trying to launch.
   * @return the incompatible message between two tasks.
   */
  protected LocalizableMessage getIncompatibilityMessage(Task taskRunning,
      Task taskToBeLaunched)
  {
    return INFO_CTRL_PANEL_INCOMPATIBLE_TASKS.get(
        taskRunning.getTaskDescription(), taskToBeLaunched.getTaskDescription());
  }
}
