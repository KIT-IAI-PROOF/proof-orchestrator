#!/usr/bin/python
# Call example: python3 src/main/resources/import/json_reader.py -i '["url"]' -o '["result"]'
# Then value: {"type": "VALUE", "lifeCycleId": 1, "data": {"url": "http://jsonplaceholder.typicode.com/todos/1"}, "time": 0}
# Then sync: {"type": "SYNC", "lifeCycleId": 1, "stepType": "STEP", "time": 0}

from argparse import ArgumentParser
from json import dumps, loads
from sys import stdin, stdout
import time
import math

p = ArgumentParser()
p.add_argument('--inputs', '-i', dest='inputs', default='[]', type=str)
p.add_argument('--outputs', '-o', dest='outputs', default='[]', type=str)
options, arguments = p.parse_known_args()


class Program:

    def __init__(self):
        self.c = 0.477  #
        self.m = 10  # mass in g
        self.T_ambient = 293.15  # ambient temperature
        self.power_loss_therm = 999  # input for this model
        self.communicationPoint = 0
        self.communication_step_size = 1
        self.outputs = loads(options.outputs)
        self.inputs = loads(options.inputs)
        self.T_therm = 0
        print("PROGRAM INITIALIZED WITH I={i} O={o}".format(i=self.inputs, o=self.outputs), flush=True)

    def init(self):
        print("EXECUTING INIT", flush=True)
        self.notify(0)

    def step(self):
        print("EXECUTING STEP", flush=True)
        # Model logic
        print("power_loss_therm: ", self.power_loss_therm)
        dT = self.power_loss_therm * self.communication_step_size / (self.c * self.m)
        self.T_therm += dT
        print("After the Calculation the T_Therm is ", self.T_therm)
        # while True:
        #     if self.power_loss != 999:
        #         print("The power_loss is " + self.power_loss)
        #         # Calcualte the resistance which is dependent on the temperature
        #         dT = self.power_loss * self.communication_step_size / (self.c * self.m)
        #         self.T += dT
        #         self.power_loss = 999
        #         break
        #     print("in while")
        #     time.sleep(5)

        self.get_data(1)
        self.notify(1)

    def finalize(self):
        print("EXECUTING FINALIZE", flush=True)
        self.notify(2)

    def set_data(self, data):
        for key, value in data.items():
            setattr(self, key, value)

    def get_data(self, lifecycle_id):
        result = {}
        for output in self.outputs:
            if hasattr(self, output):
                result[output] = getattr(self, output)
            else:
                pass
        message = {
            "type": "VALUE",
            "lifeCycleId": lifecycle_id,
            "data": result,
            "time": 0
        }
        stdout.write(dumps(message) + '\n')

    @staticmethod
    def notify(lifecycle_id):
        message = {
            "type": "LIFECYCLENOTIFY",
            "status": "COMPLETED",
            "lifeCycleId": lifecycle_id,
            "time": 0
        }
        stdout.write(dumps(message) + '\n')
        # print("NOTIFY SEND", flush=True)

    def main(self) -> None:
        print("thermModel main")
        while True:
            print("thermModel readline")
            line = stdin.readline()
            print("thermModel after readline")
            message = loads(line)
            message_type = message["type"]
            if message_type == "VALUE":
                print("THERM PROGRAM RECEIVED VALUEMESSAGE={0}".format(message), flush=True)
                message_data = message["data"]
                self.set_data(message_data)
            elif message_type == "SYNC":
                print("THERM PROGRAM RECEIVED SYNCMESSAGE={0}".format(message), flush=True)
                self.communicationPoint = message["communicationPoint"]
                self.communication_step_size = message["communicationStepSize"]
                message_step = message["stepType"]
                if message_step == "INIT":
                    self.init()
                elif message_step == "STEP":
                    self.step()
                elif message_step == "FINALIZE":
                    self.finalize()


if __name__ == '__main__':
    program = Program()
    program.main()
