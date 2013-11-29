package com.devicehive.client.example.gateway;


import com.devicehive.client.api.gateway.HiveDeviceGateway;
import com.devicehive.client.model.*;
import com.devicehive.client.model.exceptions.HiveException;
import org.apache.commons.cli.*;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeviceGatewayExample {
    private static Logger logger = LoggerFactory.getLogger(DeviceGatewayExample.class);
    private static ScheduledExecutorService commandsUpdater = Executors.newSingleThreadScheduledExecutor();
    private final HelpFormatter HELP_FORMATTER = new HelpFormatter();
    private Options options = new Options();
    private URI rest;
    private boolean isParseable = true;
    private Transport transport;

    public static void main(String... args) {
        DeviceGatewayExample example = new DeviceGatewayExample();
        example.run(args);
    }

    private Device createDeviceToSave() {
        Device device = new Device();
        device.setId(UUID.randomUUID().toString());
        device.setKey(UUID.randomUUID().toString());
        device.setStatus("new device example status");
        device.setName("example device");
        DeviceClass deviceClass = new DeviceClass();
        deviceClass.setName("example device class name");
        deviceClass.setVersion("v1");
        deviceClass.setPermanent(true);
        Set<Equipment> equipmentSet = new HashSet<>();
        Equipment equipment = new Equipment();
        equipment.setName("example equipment name");
        equipment.setCode(UUID.randomUUID().toString());
        equipment.setType("example");
        equipmentSet.add(equipment);
        deviceClass.setEquipment(equipmentSet);
        device.setDeviceClass(deviceClass);
        Network network = new Network();
        network.setId(1L);
        network.setName("VirtualLed Sample Network");
        device.setNetwork(network);
        return device;
    }

    private void updateCommands(HiveDeviceGateway hdg, String deviceId, String deviceKey) {
        Queue<Pair<String, DeviceCommand>> commandsQueue = hdg.getCommandsQueue();
        Iterator<Pair<String, DeviceCommand>> commandIterator = commandsQueue.iterator();
        while (commandIterator.hasNext()) {
            Pair<String, DeviceCommand> pair = commandIterator.next();
            if (pair.getLeft().equals(deviceId)) {
                DeviceCommand command = pair.getRight();
                command.setStatus("procceed");
                hdg.updateCommand(deviceId, deviceKey, command);
                commandIterator.remove();
                logger.debug("command with id {} is updated", command.getId());
            }

        }
    }

    private DeviceNotification createNotification() {
        DeviceNotification notification = new DeviceNotification();
        notification.setNotification("example notification");
        notification.setParameters(new JsonStringWrapper("{\"params\": example_param}"));
        return notification;
    }

    private void killUpdater() {
        commandsUpdater.shutdown();
        try {
            if (!commandsUpdater.awaitTermination(5, TimeUnit.SECONDS)) {
                commandsUpdater.shutdownNow();
                if (!commandsUpdater.awaitTermination(5, TimeUnit.SECONDS))
                    logger.warn("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            logger.warn(ie.getMessage(), ie);
            commandsUpdater.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void example(final HiveDeviceGateway hdg) {
        try {
            //save device
            final Device deviceToSave = createDeviceToSave();
            hdg.saveDevice(deviceToSave.getId(), deviceToSave.getKey(), deviceToSave);
            logger.debug("device saved");

            //get device
            Device savedDevice = hdg.getDevice(deviceToSave.getId(), deviceToSave.getKey());
            logger.debug("saved device: id {}, name {}, status {}, data {}, device class id {}, " +
                    "device class name {}, device class version {}", savedDevice.getId(),
                    savedDevice.getName(), savedDevice.getStatus(), savedDevice.getData(),
                    savedDevice.getDeviceClass().getId(), savedDevice.getDeviceClass().getName(),
                    savedDevice.getDeviceClass().getVersion());

            //update device
            deviceToSave.setStatus("updated example status");
            hdg.saveDevice(deviceToSave.getId(), deviceToSave.getKey(), deviceToSave);
            logger.debug("device updated");

            //get device
            Device updatedDevice = hdg.getDevice(deviceToSave.getId(), deviceToSave.getKey());
            logger.debug("updated device: id {}, name {}, status {}, data {}, device class id {}, " +
                    "device class name {}, device class version {}", updatedDevice.getId(),
                    updatedDevice.getName(), updatedDevice.getStatus(), updatedDevice.getData(),
                    updatedDevice.getDeviceClass().getId(), updatedDevice.getDeviceClass().getName(),
                    updatedDevice.getDeviceClass().getVersion());

            //subscribe for commands
            try {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy-mm-dd hh:mm:ss");
                Date startDate = formatter.parse("2013-10-11 13:12:00");
                hdg.subscribeForCommands(deviceToSave.getId(), deviceToSave.getKey(),
                        new Timestamp(startDate.getTime()));
                logger.debug("device subscribed for commands");
            } catch (ParseException e) {
                logger.error(e.getMessage(), e);
            }

            //update commands
            commandsUpdater.scheduleAtFixedRate(new Runnable() {
                @Override
                public void run() {
                    while (!Thread.currentThread().isInterrupted()) {
                        updateCommands(hdg, deviceToSave.getId(), deviceToSave.getKey());
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);

            //notification insert
            hdg.insertNotification(deviceToSave.getId(), deviceToSave.getKey(), createNotification());

            try {
                Thread.currentThread().join(5_000);
                hdg.unsubscribeFromCommands(deviceToSave.getId(), deviceToSave.getKey());
                Thread.currentThread().join(5_000);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        } catch (HiveException e) {
            logger.error(e.getMessage(), e);
        } finally {
            killUpdater();
            try {
                hdg.close();
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void printUsage() {
        HELP_FORMATTER.printHelp("SingleHiveDeviceExample", options);
    }

    private void parseArguments(String... args) {
        CommandLineParser parser = new BasicParser();
        try {
            CommandLine cmdLine = parser.parse(options, args);
            rest = URI.create(cmdLine.getOptionValue("rest"));
            transport = cmdLine.hasOption("use_sockets") ? Transport.PREFER_WEBSOCKET : Transport.REST_ONLY;
        } catch (org.apache.commons.cli.ParseException e) {
            logger.error("unable to parse command line arguments!");
            printUsage();
            isParseable = false;
        }
    }

    private void initOptions() {
        Option restUrl = OptionBuilder.hasArg()
                .withArgName("rest")
                .withDescription("REST service URL")
                .isRequired(true)
                .create("rest");
        Option transport = OptionBuilder.hasArg(false)
                .withDescription("if set use sockets")
                .create("use_sockets");
        options.addOption(restUrl);
        options.addOption(transport);
    }

    public void run(String... args) {
        initOptions();
        parseArguments(args);
        if (isParseable) {
            HiveDeviceGateway hdg = new HiveDeviceGateway(rest, transport);
            example(hdg);
        }

    }

}