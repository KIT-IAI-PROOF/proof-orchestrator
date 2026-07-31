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
        self.T_ambient = 293.15
        self.T0 = 273.15
        self.R0 = 100
        self.alpha = 3.85e-2
        self.U = 7.00
        self.Current = []
        self.power_loss = 0.0
        self.T = 0.0
        self.tmax = 10
        self.outputs = loads(options.outputs)
        self.inputs = loads(options.inputs)
        # print("PROGRAM INITIALIZED WITH I={i} O={o}".format(i=self.inputs, o=self.outputs), flush=True)

    def init(self):
        # print("EXECUTING INIT", flush=True)
        self.notify(0)

    def step(self):
        # print("EXECUTING STEP", flush=True)
        # Model logic
        print("communicationPoint:" , self.communicationPoint)
        print("tmax:" , self.tmax)
        if self.communicationPoint + 1 < self.tmax:
            if self.communicationPoint == 0:
                print("T_ambient ", self.T_ambient, "is used.")
                self.R_T(self.T_ambient)
            else:
                while True:
                    if self.T != 0.0:
                        print("The temperature is at " + str(self.T) + " K at communicationPoint" + str(self.communicationPoint))
                        self.R_T(self.T)
                        self.T = 0.0
                        break
                    print("in while")
                    time.sleep(5)

        else:
            self.finalize()
        self.get_data(1)
        self.notify(1)

    def finalize(self):
        # print("EXECUTING FINALIZE", flush=True)
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
        while True:
            line = stdin.readline()
            message = loads(line)
            message_type = message["type"]
            if message_type == "VALUE":
                # print("PROGRAM RECEIVED VALUEMESSAGE={0}".format(message), flush=True)
                message_data = message["data"]
                self.set_data(message_data)
            elif message_type == "SYNC":
                # print("PROGRAM RECEIVED SYNCMESSAGE={0}".format(message), flush=True)
                message_step = message["stepType"]
                if message_step == "INIT":
                    self.init()
                elif message_step == "STEP":
                    self.step()
                elif message_step == "FINALIZE":
                    self.finalize()

    def R_T(self,T): # Calcualte the resistance which is dependant on the temperature
        R = self.R0 * math.exp(self.alpha * (T - self.T0)) #resistance at the current temperature
        self.power_loss = (self.U ** 2) / R
        self.Current.append(self.U / R)

if __name__ == '__main__':
    program = Program()
    program.main()
