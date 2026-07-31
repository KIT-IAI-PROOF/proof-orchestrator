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
        self.T0 = 273.15
        self.R0 = 100
        self.alpha = 3.85e-2
        self.U = 7.00
        self.Current = []
        self.T_elec = 0.0
        self.tmax = 2000
        self.communicationPoint = 0
        self.communication_step_size = 1
        self.power_loss_elec_3 = 0.0  # output
        self.outputs = loads(options.outputs)
        self.inputs = loads(options.inputs)
        self.R_T(293.15)
        print("PROGRAM INITIALIZED WITH I={i} O={o}".format(i=self.inputs, o=self.outputs), flush=True)

    def init(self):
        print("EXECUTING INIT", flush=True)
        self.notify(0)

    def step(self):
        print("EXECUTING STEP", flush=True)
        # Model logic
        print("communicationPoint:", self.communicationPoint)
        print("tmax:", self.tmax)
        if self.communicationPoint + 1 < self.tmax:
            print("T_elec ", self.T_elec, "is used.")
            self.R_T(self.T_elec)
            print("after calculation the new power_loss_elec_3 is ", self.power_loss_elec_3)
            print("The current is: ")
            print(self.Current, flush=True)
            self.get_data(1)
        else:
            print("communication point + 1 > tmax ---> no more calculation here!")
            print("The final current is: ")
            print(self.Current)
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
        print("elecModel-1 main")
        while True:
            print("elecModel-3 readline")
            line = stdin.readline()
            print("elecModel-3 after readline")
            message = loads(line)
            message_type = message["type"]
            if message_type == "VALUE":
                print("ELEC-3 PROGRAM RECEIVED VALUEMESSAGE={0}".format(message), flush=True)
                message_data = message["data"]
                self.set_data(message_data)
            elif message_type == "SYNC":
                print("ELEC-3 PROGRAM RECEIVED SYNCMESSAGE={0}".format(message), flush=True)
                self.communicationPoint = message["communicationPoint"]
                self.communication_step_size = message["communicationStepSize"]
                message_step = message["stepType"]
                if message_step == "INIT":
                    self.init()
                elif message_step == "STEP":
                    self.step()
                elif message_step == "FINALIZE":
                    self.finalize()

    def R_T(self, T):  # Calculate the resistance which is dependent on the temperature
        R = self.R0 * math.exp(self.alpha * (T - self.T0))  # resistance at the current temperature
        self.power_loss_elec_3 = (self.U ** 2) / R
        self.Current.append(self.U / R)


if __name__ == '__main__':
    program = Program()
    program.main()
