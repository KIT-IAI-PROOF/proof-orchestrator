#!/usr/bin/python
# Call example: python3 src/main/resources/import/json_reader.py -i '["url"]' -o '["result"]'
# Then value: {"type": "VALUE", "lifeCycleId": 1, "data": {"url": "http://jsonplaceholder.typicode.com/todos/1"}, "time": 0}
# Then sync: {"type": "SYNC", "lifeCycleId": 1, "stepType": "STEP", "time": 0}
from argparse import ArgumentParser
from json import dumps, loads
from sys import stdin, stdout
from requests import post
import requests

p = ArgumentParser()
p.add_argument('--inputs', '-i', dest='inputs', default='[]', type=str)
p.add_argument('--outputs', '-o', dest='outputs', default='[]', type=str)
options, arguments = p.parse_known_args()


class Program:

    def __init__(self):
        self.ts_writer_url = ""
        self.result = {}
        self.outputs = loads(options.outputs)
        self.inputs = loads(options.inputs)
        print("inputs: ", self.inputs, " outputs:", self.outputs)
        # print("PROGRAM INITIALIZED WITH I={i} O={o}".format(i=self.inputs, o=self.outputs), flush=True)

    def init(self):
        # print("EXECUTING INIT", flush=True)
        self.notify(0)

    def step(self):
        # Url has to be set in advance (init)
        # print("EXECUTING STEP", flush=True)
        print("step: post ts data:")
        print(self.input)
        print("to the url" + self.ts_writer_url)
        headers = {
            'Content-Type':'application/json'
        }
        print(type(self.input))
        a = (self.input["output"])
        print(type(a))
        payload = dumps(a)
        print(type(payload))
        response = requests.request("POST",self.ts_writer_url, data=payload, headers=headers)
        print("post response:", response)
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
        print("get_data finish:")
        print(message)

    @staticmethod
    def notify(lifecycle_id):
        message = {
            "type": "LIFECYCLENOTIFY",
            "status": "COMPLETED",
            "lifeCycleId": lifecycle_id,
            "time": 0
        }
        stdout.write(dumps(message) + '\n')
        print("NOTIFY SEND:", flush=True)
        print(message)


    def main(self) -> None:
        while True:
            line = stdin.readline()
            message = loads(line)
            message_type = message["type"]
            print("stdin.readline:", message)
            if message_type == "VALUE":
                # print("PROGRAM RECEIVED VALUEMESSAGE={0}".format(message), flush=True)
                message_data = message["data"]
                self.set_data(message_data)
                print("value message:", message_data)
            elif message_type == "SYNC":
                # print("PROGRAM RECEIVED SYNCMESSAGE={0}".format(message), flush=True)
                message_step = message["stepType"]
                print("Sync message:", message_step)
                if message_step == "INIT":
                    print("init starting")
                    self.init()
                elif message_step == "STEP":
                    print("step starting")
                    self.step()
                elif message_step == "FINALIZE":
                    print("finalize starting")
                    self.finalize()


if __name__ == '__main__':
    program = Program()
    program.main()
